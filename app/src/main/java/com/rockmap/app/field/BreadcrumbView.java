package com.rockmap.app.field;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public final class BreadcrumbView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint start = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint end = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<GeoMath.Point> points = new ArrayList<>();
    private GeoMath.Point current;

    public BreadcrumbView(Context context) {
        super(context);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(3));
        line.setColor(0xff202020);
        start.setColor(0xff2e7d32);
        end.setColor(0xffc62828);
        setMinimumHeight(dp(220));
    }

    public void setPoints(List<GeoMath.Point> points) {
        this.points = points == null ? new ArrayList<>() : new ArrayList<>(points);
        invalidate();
    }

    public void setCurrent(GeoMath.Point current) {
        this.current = current;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty()) return;
        double minLat=points.get(0).lat, maxLat=minLat, minLon=points.get(0).lon, maxLon=minLon;
        for (GeoMath.Point p : points) {
            minLat=Math.min(minLat,p.lat); maxLat=Math.max(maxLat,p.lat);
            minLon=Math.min(minLon,p.lon); maxLon=Math.max(maxLon,p.lon);
        }
        if (current != null) {
            minLat=Math.min(minLat,current.lat); maxLat=Math.max(maxLat,current.lat);
            minLon=Math.min(minLon,current.lon); maxLon=Math.max(maxLon,current.lon);
        }
        double latSpan=Math.max(0.000001d,maxLat-minLat);
        double lonSpan=Math.max(0.000001d,maxLon-minLon);
        float pad=dp(18);
        float w=Math.max(1f,getWidth()-pad*2f), h=Math.max(1f,getHeight()-pad*2f);
        float lastX=0,lastY=0;
        for (int i=0;i<points.size();i++) {
            GeoMath.Point p=points.get(i);
            float x=pad+(float)((p.lon-minLon)/lonSpan)*w;
            float y=pad+(1f-(float)((p.lat-minLat)/latSpan))*h;
            if (i>0) canvas.drawLine(lastX,lastY,x,y,line);
            lastX=x; lastY=y;
        }
        GeoMath.Point first=points.get(0), finalPoint=points.get(points.size()-1);
        canvas.drawCircle(x(first,minLon,lonSpan,pad,w),y(first,minLat,latSpan,pad,h),dp(6),start);
        canvas.drawCircle(x(finalPoint,minLon,lonSpan,pad,w),y(finalPoint,minLat,latSpan,pad,h),dp(6),end);
        if (current != null) {
            Paint now=new Paint(Paint.ANTI_ALIAS_FLAG); now.setColor(0xff1565c0);
            canvas.drawCircle(x(current,minLon,lonSpan,pad,w),y(current,minLat,latSpan,pad,h),dp(7),now);
        }
    }

    private float x(GeoMath.Point p,double min,double span,float pad,float width){return pad+(float)((p.lon-min)/span)*width;}
    private float y(GeoMath.Point p,double min,double span,float pad,float height){return pad+(1f-(float)((p.lat-min)/span))*height;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
