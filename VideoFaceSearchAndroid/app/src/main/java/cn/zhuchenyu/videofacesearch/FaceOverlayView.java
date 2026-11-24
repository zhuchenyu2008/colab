package cn.zhuchenyu.videofacesearch;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import java.util.*;

/**
 * Transparent CameraX overlay. Coordinates are in the upright analysis bitmap.
 * The matching alert is based on a similarity threshold, NOT confirmed identity.
 */
public final class FaceOverlayView extends View {
    private static final int NORMAL_FACE = Color.rgb(84,182,250);
    private static final int ALERT_RED = Color.rgb(255,49,66);
    private static final long HOLD_MS = 1600L;
    private static final long PULSE_MS = 650L;
    static final class Mark {
        final RectF box;
        final float score;
        final boolean candidate;
        Mark(RectF r,float s,boolean match){box=r;score=s;candidate=match;}
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Mark> marks=Collections.emptyList();
    private int sourceW=1,sourceH=1;
    private long markedAt=0;
    private boolean hasCandidate;
    private final float density;

    FaceOverlayView(Context ctx) {
        super(ctx);
        density=getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
    }

    /** Called on UI thread with a fully evaluated camera sample. */
    public void show(List<FaceEngine.Face> faces,float[] query,int width,int height,float threshold) {
        ArrayList<Mark> next=new ArrayList<>();
        boolean matched=false;
        for(FaceEngine.Face face:faces){
            if(face.bounds.length<4)continue;
            float score=FaceEngine.cosine(query,face.embedding);
            boolean candidate=Float.isFinite(score) && score>=threshold;
            matched|=candidate;
            next.add(new Mark(new RectF(face.bounds[0],face.bounds[1],face.bounds[2],face.bounds[3]),score,candidate));
        }
        marks=next;
        hasCandidate=matched;
        sourceW=Math.max(1,width);
        sourceH=Math.max(1,height);
        markedAt=SystemClock.uptimeMillis();
        invalidate();
    }
    public void clear() {
        marks=Collections.emptyList();
        hasCandidate=false;
        markedAt=0;
        invalidate();
    }
    private float px(float x){return density*x;}

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if(marks.isEmpty() || getWidth()==0 || getHeight()==0)return;
        final long age=SystemClock.uptimeMillis()-markedAt;
        if(age>HOLD_MS) {
            clear();
            return;
        }
        final float scale=Math.max(getWidth()/(float)sourceW,getHeight()/(float)sourceH);
        final float left=(getWidth()-sourceW*scale)/2f;
        final float top=(getHeight()-sourceH*scale)/2f;

        canvas.save();
        canvas.clipRect(0,0,getWidth(),getHeight());
        for(Mark face:marks) {
            RectF box=new RectF(
                left+face.box.left*scale, top+face.box.top*scale,
                left+face.box.right*scale, top+face.box.bottom*scale);
            if(box.width()<=0||box.height()<=0)continue;
            final float corner=Math.max(px(11),Math.min(box.width(),box.height())*.24f);
            final int color=face.candidate?ALERT_RED:NORMAL_FACE;
            p.clearShadowLayer();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(face.candidate?3.7f:2.7f));
            p.setColor(color);
            p.setAlpha(255);
            Path k=new Path();
            k.moveTo(box.left,box.top+corner);k.lineTo(box.left,box.top);k.lineTo(box.left+corner,box.top);
            k.moveTo(box.right-corner,box.top);k.lineTo(box.right,box.top);k.lineTo(box.right,box.top+corner);
            k.moveTo(box.right,box.bottom-corner);k.lineTo(box.right,box.bottom);k.lineTo(box.right-corner,box.bottom);
            k.moveTo(box.left+corner,box.bottom);k.lineTo(box.left,box.bottom);k.lineTo(box.left,box.bottom-corner);
            canvas.drawPath(k,p);

            final String title=String.format(Locale.US,face.candidate?"匹配候选  %.3f":"人脸  %.3f",face.score);
            p.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));
            p.setTextSize(px(12));
            final float badgeWidth=p.measureText(title)+px(18);
            final float badgeHeight=px(25);
            final float x=Math.max(px(4),Math.min(box.left,getWidth()-badgeWidth-px(4)));
            final float y=Math.max(badgeHeight+px(3),box.top-px(7));
            p.setStyle(Paint.Style.FILL);
            p.setColor(face.candidate?0xE9A41424:0xE9164268);
            canvas.drawRoundRect(x,y-badgeHeight,x+badgeWidth,y,px(7),px(7),p);
            p.setColor(Color.WHITE);
            canvas.drawText(title,x+px(9),y-px(7),p);
        }

        if(hasCandidate)drawMatchAlert(canvas,age);
        canvas.restore();
        // Always clear stale boxes; during an alert, animate a visible red pulse.
        postInvalidateDelayed(hasCandidate?80L:Math.min(250L,HOLD_MS-age+1L));
    }

    private void drawMatchAlert(Canvas canvas,long age) {
        float phase=(float)(2*Math.PI*(age%PULSE_MS)/(double)PULSE_MS);
        float pulse=.5f+.5f*(float)Math.sin(phase);
        int alpha=140+Math.round(115*pulse);
        float border=px(5f);
        float inset=border/2f+px(2f);

        p.clearShadowLayer();
        p.setColor(ALERT_RED);
        p.setAlpha(alpha);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(border);
        p.setShadowLayer(px(9),0,0,0xB0FF3142);
        canvas.drawRoundRect(new RectF(inset,inset,getWidth()-inset,getHeight()-inset),
            px(14),px(14),p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);

        final String headline="出现匹配目标";
        final String detail="相似度达到阈值 · 请人工核验";
        p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        p.setTextSize(px(19));
        float width=Math.min(getWidth()-px(24),Math.max(px(238),p.measureText(headline)+px(45)));
        float x=(getWidth()-width)/2f;
        float y=px(46);
        p.setColor(0xFFB51D2C);
        p.setAlpha(220+Math.round(35*pulse));
        canvas.drawRoundRect(x,y-px(30),x+width,y+px(35),px(12),px(12),p);
        p.setColor(Color.WHITE);
        p.setAlpha(255);
        p.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(headline,getWidth()/2f,y,p);
        p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        p.setTextSize(px(11));
        canvas.drawText(detail,getWidth()/2f,y+px(21),p);
        p.setTextAlign(Paint.Align.LEFT);
    }
}
