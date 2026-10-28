package bot.paradox.wifi;
import android.animation.*;import android.content.*;import android.graphics.*;import android.graphics.drawable.*;import android.view.*;import android.view.animation.*;
/**
 * Hero status element, drawn on Canvas. Monochrome only.
 *  CONNECTED  : solid core disc with a check, two quiet halo rings (static: no animation while idle).
 *  SIGNING_*  : hollow core, slow breathing halo + one orbiting arc (runs only while visible and busy).
 *  SIGNED_OUT : hollow core, Wi-Fi glyph.   AWAY: dashed core, Wi-Fi-off glyph.   ATTENTION: hollow core, "i".
 * State changes cross-fade the core and spring the glyph in.
 */
final class OrbView extends View {
 Ui.Pal pal;String state="IDLE";float fill=0f,fillFrom=0f,fillTo=0f,glyphIn=1f,breath=0f,spin=0f;Drawable glyph,oldGlyph;
 final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG),glow=new Paint(Paint.ANTI_ALIAS_FLAG);ValueAnimator loop,change;final RectF oval=new RectF();
 OrbView(Context c,Ui.Pal pal){super(c);this.pal=pal;setLayerType(LAYER_TYPE_HARDWARE,null);glyph=glyphFor(state);}
 static boolean solid(String s){return "CONNECTED".equals(s);}
 static boolean busy(String s){return s.startsWith("SIGNING");}
 Drawable glyphFor(String s){int r="CONNECTED".equals(s)?R.drawable.ic_check:"AWAY".equals(s)?R.drawable.ic_wifi_off:"ATTENTION".equals(s)?R.drawable.ic_info:"SETUP".equals(s)?R.drawable.ic_person:"OFF".equals(s)?R.drawable.ic_wifi_off:R.drawable.ic_wifi;return getContext().getDrawable(r).mutate();}
 void setState(String s,boolean animate){if(s.equals(state)&&glyph!=null)return;String was=state;state=s;oldGlyph=glyph;glyph=glyphFor(s);fillFrom=fill;fillTo=solid(s)?1f:0f;
  if(change!=null)change.cancel();
  if(!animate||!isAttachedToWindow()){fill=fillTo;glyphIn=1f;oldGlyph=null;invalidate();syncLoop();return;}
  change=ValueAnimator.ofFloat(0f,1f);change.setDuration(520);change.setInterpolator(new PathInterpolator(0.2f,0f,0f,1f));
  change.addUpdateListener(a->{float t=(float)a.getAnimatedValue();fill=fillFrom+(fillTo-fillFrom)*t;glyphIn=t;invalidate();});
  change.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){oldGlyph=null;glyphIn=1f;invalidate();}});change.start();syncLoop();}
 void syncLoop(){boolean want=busy(state)&&isAttachedToWindow()&&getWindowVisibility()==VISIBLE;
  if(want&&loop==null){loop=ValueAnimator.ofFloat(0f,1f);loop.setDuration(2400);loop.setRepeatCount(ValueAnimator.INFINITE);loop.setInterpolator(new LinearInterpolator());
   loop.addUpdateListener(a->{float t=(float)a.getAnimatedValue();breath=(float)(0.5-0.5*Math.cos(t*2*Math.PI));spin=t*360f;invalidate();});loop.start();}
  else if(!want&&loop!=null){loop.cancel();loop=null;breath=0f;invalidate();}}
 @Override protected void onAttachedToWindow(){super.onAttachedToWindow();syncLoop();}
 @Override protected void onDetachedFromWindow(){if(loop!=null){loop.cancel();loop=null;}if(change!=null)change.end();super.onDetachedFromWindow();}
 @Override protected void onWindowVisibilityChanged(int v){super.onWindowVisibilityChanged(v);syncLoop();}
 int cap=-1; // v5.2: the hero may shrink the orb so the usage card fits
 @Override protected void onMeasure(int w,int h){int s=Math.min(MeasureSpec.getSize(w),cap>0?cap:Ui.dp(getContext(),280));setMeasuredDimension(s,s);}
 int a(int color,float alpha){return (color&0x00ffffff)|(Math.round(255*alpha*(((color>>>24)&0xff)/255f))<<24);}
 @Override protected void onDraw(Canvas c){float w=getWidth(),cx=w/2f,cy=getHeight()/2f,R=w/2f-Ui.dp(getContext(),2),d=Ui.density(getContext());
  boolean b=busy(state),away="AWAY".equals(state)||"OFF".equals(state);
  // soft grayscale glow (brighter when connected, breathing while busy)
  float glowA=(pal.dark?0.10f:0.07f)+(pal.dark?0.10f:0.06f)*fill+(b?(pal.dark?0.08f:0.05f)*breath:0f);
  glow.setShader(new RadialGradient(cx,cy,R,new int[]{a(pal.primary,glowA),a(pal.primary,glowA*0.45f),a(pal.primary,0f)},new float[]{0.35f,0.7f,1f},Shader.TileMode.CLAMP));c.drawCircle(cx,cy,R,glow);
  // halo rings (outer breathes while busy)
  float outer=R*(0.86f+0.05f*breath);p.setStyle(Paint.Style.FILL);p.setColor(a(pal.primary,(pal.dark?0.03f:0.025f)+(b?0.02f*breath:0f)+0.015f*fill));c.drawCircle(cx,cy,outer,p);
  p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1f,d*0.75f));p.setColor(a(pal.primary,0.06f+0.04f*fill));c.drawCircle(cx,cy,outer,p);
  float mid=R*0.68f;p.setStyle(Paint.Style.FILL);p.setColor(a(pal.primary,(pal.dark?0.045f:0.04f)+0.025f*fill));c.drawCircle(cx,cy,mid,p);
  p.setStyle(Paint.Style.STROKE);p.setColor(a(pal.primary,0.08f+0.05f*fill));c.drawCircle(cx,cy,mid,p);
  if(b){p.setStrokeWidth(d*3f);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(a(pal.primary,0.75f));oval.set(cx-mid,cy-mid,cx+mid,cy+mid);c.drawArc(oval,spin-90f,62f,false,p);
   p.setColor(a(pal.primary,0.28f));oval.set(cx-outer,cy-outer,cx+outer,cy+outer);c.drawArc(oval,-spin*0.6f+90f,38f,false,p);}
  // core
  float core=R*0.46f;
  if(fill<1f){p.setStyle(Paint.Style.FILL);p.setColor(pal.dark?0xff161618:0xffffffff);c.drawCircle(cx,cy,core,p);
   p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d*1.75f);p.setColor(a(pal.primary,away?0.35f:0.55f));
   if(away)p.setPathEffect(new DashPathEffect(new float[]{d*5,d*5},0));c.drawCircle(cx,cy,core,p);p.setPathEffect(null);}
  if(fill>0f){p.setStyle(Paint.Style.FILL);p.setColor(pal.primary);c.drawCircle(cx,cy,core*(0.6f+0.4f*fill),p);}
  // glyph
  int gs=Math.round(core*0.80f);
  int glyphColor=fill>0.5f?pal.onPrimary:(away?a(pal.primary,0.55f):pal.primary);
  if(oldGlyph!=null&&glyphIn<1f){int og=fillFrom>0.5f?pal.onPrimary:pal.primary;drawGlyph(c,oldGlyph,cx,cy,gs,1f-glyphIn,1f-0.15f*glyphIn,og);}
  drawGlyph(c,glyph,cx,cy,gs,oldGlyph==null?1f:glyphIn,oldGlyph==null?1f:0.82f+0.18f*glyphIn,glyphColor);}
 void drawGlyph(Canvas c,Drawable g,float cx,float cy,int size,float alpha,float scale,int color){if(g==null||alpha<=0.01f)return;int s=Math.round(size*scale);g.setTint(color);g.setAlpha(Math.round(255*alpha));g.setBounds(Math.round(cx-s/2f),Math.round(cy-s/2f),Math.round(cx+s/2f),Math.round(cy+s/2f));g.draw(c);}
}
