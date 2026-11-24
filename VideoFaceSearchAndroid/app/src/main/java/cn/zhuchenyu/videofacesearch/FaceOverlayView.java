package cn.zhuchenyu.videofacesearch;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import java.util.*;

/** Transparent scan overlay. Bounds are in the upright, scaled analysis bitmap. */
public final class FaceOverlayView extends View {
    static final class Mark {
        final RectF box;
        final float score;
        final boolean candidate;
        Mark(RectF r,float s,boolean match){box=r;score=s;candidate=match;}
    }
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Mark> marks=Collections.emptyList();
    private int sourceW=1,sourceH=1;
    private long markedAt=0;
    private final float density;
    FaceOverlayView(Context ctx){super(ctx);density=getResources().getDisplayMetrics().density;setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
    public void show(List<FaceEngine.Face> faces,float[] query,int width,int height,float threshold){
        ArrayList<Mark> next=new ArrayList<>();
        for(FaceEngine.Face face:faces){
            if(face.bounds.length<4)continue;
            float s=FaceEngine.cosine(query,face.embedding);
            next.add(new Mark(new RectF(face.bounds[0],face.bounds[1],face.bounds[2],face.bounds[3]),s,s>=threshold));
        }
        marks=next;sourceW=Math.max(1,width);sourceH=Math.max(1,height);
        markedAt=SystemClock.uptimeMillis();
        invalidate();
    }
    public void clear(){marks=Collections.emptyList();invalidate();}
    private float px(float x){return density*x;}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(marks.isEmpty())return;
        long age=SystemClock.uptimeMillis()-markedAt;
        if(age>1600L){marks=Collections.emptyList();return;}
        int alpha=age>900?Math.max(80,255-(int)((age-900)*175/700)):255;
        float scale=Math.max(getWidth()/(float)sourceW,getHeight()/(float)sourceH);
        float left=(getWidth()-sourceW*scale)/2f,top=(getHeight()-sourceH*scale)/2f;
        canvas.save();
        canvas.clipRect(0,0,getWidth(),getHeight());
        for(Mark face:marks){
            RectF r=new RectF(left+face.box.left*scale,top+face.box.top*scale,
                              left+face.box.right*scale,top+face.box.bottom*scale);
            float corner=Math.max(px(11),Math.min(r.width(),r.height())*.24f);
            int color=face.candidate?Color.rgb(31,227,179):Color.rgb(84,182,250);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(px(2.7f));p.setColor(color);
            p.setAlpha(alpha);
            // Draw L-shaped corners instead of a heavy full rectangular outline.
            Path k=new Path();
            k.moveTo(r.left,r.top+corner);k.lineTo(r.left,r.top);k.lineTo(r.left+corner,r.top);
            k.moveTo(r.right-corner,r.top);k.lineTo(r.right,r.top);k.lineTo(r.right,r.top+corner);
            k.moveTo(r.right,r.bottom-corner);k.lineTo(r.right,r.bottom);k.lineTo(r.right-corner,r.bottom);
            k.moveTo(r.left+corner,r.bottom);k.lineTo(r.left,r.bottom);k.lineTo(r.left,r.bottom-corner);
            canvas.drawPath(k,p);
            String title=face.candidate?String.format(Locale.US,"候选  %.3f",face.score):
                    String.format(Locale.US,"人脸  %.3f",face.score);
            p.setTextSize(px(12));p.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
            float labelWidth=p.measureText(title)+px(18),labelHeight=px(25);
            float x=Math.max(4,Math.min(r.left,getWidth()-labelWidth-4));
            float y=Math.max(labelHeight+px(3),r.top-px(7));
            p.setStyle(Paint.Style.FILL);p.setColor(face.candidate?0xE9137059:0xE9164268);
            p.setAlpha(alpha);
            canvas.drawRoundRect(x,y-labelHeight,x+labelWidth,y,px(7),px(7),p);
            p.setColor(Color.WHITE);p.setAlpha(alpha);
            canvas.drawText(title,x+px(9),y-px(7),p);
        }
        canvas.restore();
        if(age>900)postInvalidateDelayed(150);
    }
}
