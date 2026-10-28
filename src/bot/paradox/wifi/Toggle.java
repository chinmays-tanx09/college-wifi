package bot.paradox.wifi;
import android.animation.*;import android.content.*;import android.graphics.*;import android.view.*;import android.view.animation.PathInterpolator;
/** Monochrome switch matching the theme: filled primary track when on, quiet zinc track when off. */
final class Toggle extends View {
 final Ui.Pal pal;boolean on;float pos;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final RectF r=new RectF();Listener l;
 interface Listener{void changed(boolean on);}
 Toggle(Context c,Ui.Pal pal,boolean on,Listener l){super(c);this.pal=pal;this.on=on;pos=on?1:0;this.l=l;setClickable(true);setOnClickListener(v->{set(!this.on,true);performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);if(this.l!=null)this.l.changed(this.on);});}
 void set(boolean v,boolean anim){on=v;if(!anim||!isAttachedToWindow()){pos=v?1:0;invalidate();return;}ValueAnimator a=ValueAnimator.ofFloat(pos,v?1:0);a.setDuration(220);a.setInterpolator(new PathInterpolator(0.2f,0,0,1));a.addUpdateListener(x->{pos=(float)x.getAnimatedValue();invalidate();});a.start();}
 @Override protected void onMeasure(int w,int h){setMeasuredDimension(Ui.dp(getContext(),52),Ui.dp(getContext(),32));}
 @Override protected void onDraw(Canvas c){float w=getWidth(),h=getHeight(),d=Ui.density(getContext());r.set(0,0,w,h);
  p.setStyle(Paint.Style.FILL);p.setColor(blend(pal.surface2,pal.primary,pos));c.drawRoundRect(r,h/2,h/2,p);
  if(pos<1){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1,d*0.75f));p.setColor(blend(pal.hairline,pal.primary,pos));r.inset(d*0.4f,d*0.4f);c.drawRoundRect(r,h/2,h/2,p);}
  float rad=h/2-d*(4-1.0f*pos),cx=h/2+(w-h)*pos;p.setStyle(Paint.Style.FILL);p.setColor(blend(pal.faint,pal.onPrimary,pos));c.drawCircle(cx,h/2,rad,p);}
 static int blend(int a,int b,float t){int ar=(a>>16)&255,ag=(a>>8)&255,ab=a&255,br=(b>>16)&255,bg=(b>>8)&255,bb=b&255;return 0xff000000|(Math.round(ar+(br-ar)*t)<<16)|(Math.round(ag+(bg-ag)*t)<<8)|Math.round(ab+(bb-ab)*t);}
}
