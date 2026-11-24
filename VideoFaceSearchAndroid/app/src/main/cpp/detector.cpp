
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <net.h>
#include <gpu.h>
#include <mat.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <memory>
#include <vector>
#include <cstring>
#include "arcface_io.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "VfsNcnn", __VA_ARGS__)

namespace {
struct Face {
    float score;
    std::array<float,4> box;
    std::array<float,10> kps;
};
std::unique_ptr<ncnn::Net> g_net;
std::unique_ptr<ncnn::Net> g_arcface;
bool g_gpu = false;

float iou(const Face& a,const Face& b) {
    float x0=std::max(a.box[0],b.box[0]),y0=std::max(a.box[1],b.box[1]);
    float x1=std::min(a.box[2],b.box[2]),y1=std::min(a.box[3],b.box[3]);
    float overlap=std::max(0.f,x1-x0)*std::max(0.f,y1-y0);
    float arA=std::max(0.f,a.box[2]-a.box[0])*std::max(0.f,a.box[3]-a.box[1]);
    float arB=std::max(0.f,b.box[2]-b.box[0])*std::max(0.f,b.box[3]-b.box[1]);
    return overlap/(arA+arB-overlap+1e-6f);
}
float clamp(float v,float maximum) { return std::min(maximum,std::max(0.f,v)); }

void decode_stride(ncnn::Extractor& ex,int stride,int width,int height,
                   float scale,int left,int top,int ow,int oh,
                   std::vector<Face>& result) {
    const std::string suffix=std::to_string(stride);
    ncnn::Mat score,bbox,kps;
    if (ex.extract(("score_"+suffix).c_str(),score)!=0 ||
        ex.extract(("bbox_"+suffix).c_str(),bbox)!=0 ||
        ex.extract(("kps_"+suffix).c_str(),kps)!=0) return;
    if(score.empty()||bbox.empty()||kps.empty()) return;
    // The 10G KPS model uses two anchors per cell with 8/16/32 feature strides.
    const int channels=std::min(2,score.c);
    for(int q=0;q<channels;q++) {
        const float* scores=score.channel(q);
        const float* bx[4]={bbox.channel(q*4),bbox.channel(q*4+1),
                            bbox.channel(q*4+2),bbox.channel(q*4+3)};
        const float* kp[10];
        for(int t=0;t<10;t++) kp[t]=kps.channel(q*10+t);
        for(int y=0;y<score.h;y++) for(int x=0;x<score.w;x++) {
            const int at=y*score.w+x;
            float p=scores[at];
            if(p<0.35f)continue;
            float cx=x*stride,cy=y*stride;
            float boxPx[4]={cx-bx[0][at]*stride,cy-bx[1][at]*stride,
                            cx+bx[2][at]*stride,cy+bx[3][at]*stride};
            Face face;
            face.score=p;
            for(int i=0;i<4;i++) {
                float v=boxPx[i]-(i%2?top:left);
                face.box[i]=clamp(v/scale,(i%2?oh:ow));
            }
            if(face.box[2]-face.box[0]<12 || face.box[3]-face.box[1]<12) continue;
            for(int k=0;k<5;k++) {
                face.kps[k*2]   = (cx+kp[k*2][at]*stride-left)/scale;
                face.kps[k*2+1] = (cy+kp[k*2+1][at]*stride-top)/scale;
            }
            result.push_back(face);
        }
    }
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_zhuchenyu_videofacesearch_GpuFaceDetector_nativeInit(
        JNIEnv* env,jobject self,jobject assets,jstring paramPath,jstring weightPath) {
    (void)self;
    g_arcface.reset();
    g_net.reset();
    if(g_gpu){ncnn::destroy_gpu_instance();g_gpu=false;}
    if(!assets) return JNI_FALSE;
    if(ncnn::create_gpu_instance()!=0 || ncnn::get_gpu_count()<1) {
        LOG("No Vulkan GPU detected by ncnn");
        ncnn::destroy_gpu_instance();
        return JNI_FALSE;
    }
    g_gpu=true;
    AAssetManager* manager=AAssetManager_fromJava(env,assets);
    if(!manager) return JNI_FALSE;
    std::unique_ptr<ncnn::Net> net(new ncnn::Net());
    net->opt.use_vulkan_compute=true;
    net->opt.num_threads=2;
    int p=net->load_param(manager,"scrfd_10g_kps-opt2.param");
    int m=net->load_model(manager,"scrfd_10g_kps-opt2.bin");
    if(p!=0 || m!=0) {
        LOG("Cannot load ncnn SCRFD model param=%d bin=%d",p,m);
        net.reset();
        ncnn::destroy_gpu_instance();
        g_gpu=false;
        return JNI_FALSE;
    }
    g_net=std::move(net);
    // Convert the exact original w600k_r50.onnx (FP32) during CI.
    // If Vulkan ArcFace initialization fails, detector continues unaffected.
    std::unique_ptr<ncnn::Net> arc(new ncnn::Net());
    arc->opt.use_vulkan_compute=true;
    arc->opt.use_fp16_storage=false;
    arc->opt.use_fp16_arithmetic=false;
    arc->opt.use_fp16_packed=false;
    arc->opt.num_threads=2;
    int ap=-1,am=-1;
    // Keep weights in filesDir so upgrading a small APK never loses the GPU model.
    if(paramPath && weightPath) {
        const char* param=env->GetStringUTFChars(paramPath,nullptr);
        const char* weights=env->GetStringUTFChars(weightPath,nullptr);
        if(param && weights){
            ap=arc->load_param(param);
            am=ap==0?arc->load_model(weights):-1;
        }
        if(param)env->ReleaseStringUTFChars(paramPath,param);
        if(weights)env->ReleaseStringUTFChars(weightPath,weights);
    }
    // Only the one-time migration installer has the large model packaged as assets.
    if(ap!=0 || am!=0){
        arc->clear();
        ap=arc->load_param(manager,"w600k_r50.ncnn.param");
        am=ap==0?arc->load_model(manager,"w600k_r50.ncnn.bin"):-1;
    }
    if(ap==0 && am==0) {
        ncnn::Mat zeros(112,112,3);
        zeros.fill(0.f);
        ncnn::Extractor probe=arc->create_extractor();
        ncnn::Mat output;
        int status=probe.input(ARC_INPUT_BLOB,zeros);
        if(status==0) status=probe.extract(ARC_OUTPUT_BLOB,output);
        if(status==0 && !output.empty() && output.total()==512u && output.elemsize==4u) {
            g_arcface=std::move(arc);
            LOG("ArcFace Vulkan 512D output verified");
        } else {
            LOG("ArcFace Vulkan probe failed status=%d total=%zu elemsize=%zu",
                status,output.total(),output.elemsize);
        }
    } else {
        LOG("ArcFace Vulkan failed loading param=%d bin=%d",ap,am);
    }
    LOG("Vulkan detector ready (recognizer=%d)",g_arcface?1:0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_cn_zhuchenyu_videofacesearch_GpuFaceDetector_nativeDetect(
        JNIEnv* env,jobject self,jobject bitmap) {
    (void)self;
    if(!g_net || !bitmap) return env->NewFloatArray(0);
    AndroidBitmapInfo info;
    if(AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS ||
       info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width==0 || info.height==0)
        return env->NewFloatArray(0);
    void* ptr=nullptr;
    if(AndroidBitmap_lockPixels(env,bitmap,&ptr)!=ANDROID_BITMAP_RESULT_SUCCESS)
        return env->NewFloatArray(0);
    const int w=(int)info.width,h=(int)info.height;
    std::vector<unsigned char> contiguous;
    unsigned char* pixels=(unsigned char*)ptr;
    if(info.stride!=info.width*4) {
        contiguous.resize((size_t)w*h*4);
        for(int y=0;y<h;y++) memcpy(contiguous.data()+y*w*4,pixels+y*info.stride,(size_t)w*4);
        pixels=contiguous.data();
    }
    const float scale=640.f/std::max(w,h);
    const int dw=std::max(1,(int)(w*scale)),dh=std::max(1,(int)(h*scale));
    ncnn::Mat input=ncnn::Mat::from_pixels_resize(pixels,ncnn::Mat::PIXEL_RGBA2RGB,w,h,dw,dh);
    AndroidBitmap_unlockPixels(env,bitmap);
    int wp=(dw+31)/32*32-dw,hp=(dh+31)/32*32-dh;
    int left=wp/2,top=hp/2;
    ncnn::Mat padded;
    ncnn::copy_make_border(input,padded,top,hp-top,left,wp-left,ncnn::BORDER_CONSTANT,0.f);
    const float means[3]={127.5f,127.5f,127.5f};
    const float norms[3]={1.f/128.f,1.f/128.f,1.f/128.f};
    padded.substract_mean_normalize(means,norms);
    ncnn::Extractor ex=g_net->create_extractor();
    if(ex.input("input.1",padded)!=0) return env->NewFloatArray(0);
    std::vector<Face> proposals;
    for(int stride : {8,16,32}) decode_stride(ex,stride,dw,dh,scale,left,top,w,h,proposals);
    std::sort(proposals.begin(),proposals.end(),
              [](const Face&a,const Face&b){return a.score>b.score;});
    std::vector<Face> keep;
    for(const Face& candidate : proposals) {
        bool suppressed=false;
        for(const Face& existing:keep) {
            if(iou(candidate,existing)>0.4f){suppressed=true;break;}
        }
        if(!suppressed) keep.push_back(candidate);
        if(keep.size()>=80)break;
    }
    std::vector<float> out;
    out.reserve(keep.size()*15);
    for(const Face& f:keep) {
        out.push_back(f.score);
        for(float v:f.box)out.push_back(v);
        for(float v:f.kps)out.push_back(v);
    }
    jfloatArray result=env->NewFloatArray((jsize)out.size());
    if(!out.empty()) env->SetFloatArrayRegion(result,0,(jsize)out.size(),out.data());
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_zhuchenyu_videofacesearch_GpuFaceDetector_nativeHasRecognizer(JNIEnv*,jobject){
    return g_arcface?JNI_TRUE:JNI_FALSE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_cn_zhuchenyu_videofacesearch_GpuFaceDetector_nativeRecognize(
        JNIEnv* env,jobject,jobject bitmap){
    if(!g_arcface||!bitmap)return nullptr;
    AndroidBitmapInfo info;
    if(AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS ||
       info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width!=112 || info.height!=112)
        return nullptr;
    void* raw=nullptr;
    if(AndroidBitmap_lockPixels(env,bitmap,&raw)!=ANDROID_BITMAP_RESULT_SUCCESS)return nullptr;
    std::vector<unsigned char> tight((size_t)112*112*4);
    const unsigned char* pixels=static_cast<const unsigned char*>(raw);
    for(int y=0;y<112;y++)
        memcpy(tight.data()+y*112*4,pixels+y*info.stride,112*4);
    AndroidBitmap_unlockPixels(env,bitmap);
    ncnn::Mat input=ncnn::Mat::from_pixels(tight.data(),ncnn::Mat::PIXEL_RGBA2RGB,112,112);
    const float means[3]={127.5f,127.5f,127.5f};
    const float norms[3]={1.f/127.5f,1.f/127.5f,1.f/127.5f};
    input.substract_mean_normalize(means,norms);
    ncnn::Extractor ex=g_arcface->create_extractor();
    if(ex.input(ARC_INPUT_BLOB,input)!=0)return nullptr;
    ncnn::Mat output;
    if(ex.extract(ARC_OUTPUT_BLOB,output)!=0 || output.empty() ||
       output.total()!=512u || output.elemsize!=4u)return nullptr;
    jfloatArray result=env->NewFloatArray(512);
    if(result) env->SetFloatArrayRegion(result,0,512,(const float*)output.data);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_zhuchenyu_videofacesearch_GpuFaceDetector_nativeClose(
        JNIEnv*,jobject) {
    g_arcface.reset();
    g_net.reset();
    if(g_gpu){ ncnn::destroy_gpu_instance();g_gpu=false; }
}
