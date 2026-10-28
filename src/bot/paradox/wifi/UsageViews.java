package bot.paradox.wifi;
import android.content.*;import android.graphics.*;import android.view.*;
/** v5.2 monochrome usage graphics, drawn on Canvas: a progress ring and a 7-day bar strip. */
final class UsageViews {
 /** Ring: faint full track, solid arc for the used fraction (or a quiet dot when there's no allowance), two-line centre. */
 static final class Ring extends View {
  final Ui.Pal pal;float frac;boolean hasCap;String top="",bottom="";final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final RectF r=new RectF();final float strokeDp;
  Ring(Context c,Ui.Pal pal,float strokeDp){super(c);this.pal=pal;this.strokeDp=strokeDp;}
  void set(float f,boolean cap,String t,String b){frac=Math.max(0,Math.min(1,f));hasCap=cap;top=t==null?"":t;bottom=b==null?"":b;setContentDescription(top+" "+bottom);invalidate();}
  @Override protected void onDraw(Canvas c){float w=getWidth(),h=getHeight(),s=Math.min(w,h),d=Ui.density(getContext()),sw=strokeDp*d;r.set((w-s)/2+sw/2,(h-s)/2+sw/2,(w+s)/2-sw/2,(h+s)/2-sw/2);
   p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(sw);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(pal.surface2);c.drawOval(r,p);
   if(hasCap&&frac>0){p.setColor(pal.primary);c.drawArc(r,-90,Math.max(2f,360f*frac),false,p);}
   p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);p.setColor(pal.text);p.setTypeface(Ui.face(getContext(),750,20));float big=s*0.25f;p.setTextSize(big);
   float cy=h/2;if(bottom.isEmpty()){c.drawText(top,w/2,cy+big*0.36f,p);return;}
   c.drawText(top,w/2,cy+big*0.12f,p);p.setTypeface(Ui.face(getContext(),600,20));p.setColor(pal.faint);float small=Math.max(9*d,s*0.13f);p.setTextSize(small);c.drawText(bottom,w/2,cy+big*0.12f+small*1.25f,p);}
 }
 /** Bars, oldest → today (today solid, earlier days muted). Optional day letters under each bar. */
 static final class Bars extends View {
  final Ui.Pal pal;long[] v=new long[0];String[] labels;long cap;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final RectF r=new RectF();
  Bars(Context c,Ui.Pal pal){super(c);this.pal=pal;}
  void set(long[] values,String[] labels,long cap){v=values==null?new long[0]:values;this.labels=labels;this.cap=cap;invalidate();}
  @Override protected void onDraw(Canvas c){int n=v.length;if(n==0)return;float d=Ui.density(getContext()),w=getWidth(),h=getHeight(),lab=labels==null?0:14*d,gap=w/n*0.38f,bw=w/n-gap;long max=1;for(long x:v)max=Math.max(max,x);if(cap>0)max=Math.max(max,cap);
   float area=h-lab;
   if(cap>0&&labels!=null){float y=area-area*((float)cap/max);p.setColor(pal.hairline);p.setStrokeWidth(Math.max(1,d*0.75f));p.setPathEffect(new DashPathEffect(new float[]{3*d,3*d},0));c.drawLine(0,y,w,y,p);p.setPathEffect(null);}
   for(int i=0;i<n;i++){float x=i*(bw+gap)+gap/2,bh=v[i]<=0?0:Math.max(3*d,area*((float)v[i]/max));p.setStyle(Paint.Style.FILL);p.setColor(i==n-1?pal.primary:pal.faint);
    if(bh<=0){p.setColor(pal.surface2);r.set(x,area-3*d,x+bw,area);}else r.set(x,area-bh,x+bw,area);c.drawRoundRect(r,Math.min(bw/2,4*d),Math.min(bw/2,4*d),p);
    if(labels!=null&&i<labels.length){p.setColor(i==n-1?pal.text:pal.faint);p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Ui.face(getContext(),i==n-1?750:600,20));p.setTextSize(11*d);c.drawText(labels[i],x+bw/2,h-2*d,p);}}}
 }
}
