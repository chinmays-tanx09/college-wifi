package bot.paradox.wifi;
import android.content.*;import android.content.res.*;import android.graphics.*;import android.graphics.drawable.*;import android.text.*;import android.util.TypedValue;import android.view.*;import android.widget.*;
/** Design system: soft monochrome palette (light + dark), Nunito (rounded) type scale, 8dp grid, soft-shadow surfaces, pills, rows. */
final class Ui {
 // ---------- palette ----------
 static final class Pal {
  final boolean dark;final int bg,surface,surface2,hairline,text,muted,faint,primary,onPrimary,scrim,ripple,shadow;
  Pal(boolean d){dark=d;
   if(d){bg=0xff0e0e10;surface=0xff1a1a1d;surface2=0xff232327;hairline=0xff2a2a2f;text=0xffededed;muted=0xffa3a3aa;faint=0xff6f6f77;primary=0xffe6e6e6;onPrimary=0xff111113;scrim=0x99000000;ripple=0x26ffffff;shadow=0x66000000;}
   else{bg=0xfff6f6f4;surface=0xffffffff;surface2=0xffeeeeec;hairline=0xffe8e8e5;text=0xff141416;muted=0xff5e5e66;faint=0xff9e9ea5;primary=0xff1c1c1f;onPrimary=0xfff6f6f4;scrim=0x47000000;ripple=0x1a000000;shadow=0x1a1c1c24;}}
 }
 // ---------- theme preference ----------
 static String themePref(Context c){return Store.p(c).getString("theme","system");}
 static boolean isDark(Context c){String t=themePref(c);if("dark".equals(t))return true;if("light".equals(t))return false;
  return (c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;}
 /** API 31+: tell the system our choice so the splash screen, dialogs and -night resources follow it too. */
 static void applyNightMode(Context c){if(android.os.Build.VERSION.SDK_INT<31)return;try{android.app.UiModeManager u=(android.app.UiModeManager)c.getSystemService(Context.UI_MODE_SERVICE);String t=themePref(c);
  u.setApplicationNightMode("dark".equals(t)?android.app.UiModeManager.MODE_NIGHT_YES:"light".equals(t)?android.app.UiModeManager.MODE_NIGHT_NO:android.app.UiModeManager.MODE_NIGHT_AUTO);}catch(Throwable ignored){}}

 // ---------- type ----------
 static final android.util.SparseArray<Typeface> faces=new android.util.SparseArray<>();
 static Typeface face(Context c,int weight,int opsz){int key=weight*100+opsz;Typeface t=faces.get(key);if(t!=null)return t;
  String axes="'wght' "+weight;
  try{t=new Typeface.Builder(c.getAssets(),"fonts/Nunito.ttf").setFontVariationSettings(axes).build();}catch(Throwable e){t=null;}
  if(t==null&&System.getProperty("paradox.font")!=null)try{t=new Typeface.Builder(new java.io.File(System.getProperty("paradox.font"))).setFontVariationSettings(axes).build();}catch(Throwable e){t=null;}
  if(t==null)t=Typeface.create(Typeface.SANS_SERIF,weight>=550?Typeface.BOLD:Typeface.NORMAL);faces.put(key,t);return t;}
 static float density(Context c){return c.getResources().getDisplayMetrics().density;}
 static int dp(Context c,float v){return Math.round(v*density(c));}
 static TextView text(Context c,String s,float sp,int weight,int color,float tracking){TextView t=new TextView(c);t.setText(s);t.setTextSize(TypedValue.COMPLEX_UNIT_SP,sp);
  t.setTypeface(face(c,weight,sp>=24?32:sp>=17?20:14));t.setTextColor(color);t.setLetterSpacing(tracking);t.setIncludeFontPadding(false);t.setLineSpacing(0,1.18f);return t;}
 static TextView display(Context c,Pal p,String s){return text(c,s,36,700,p.text,-0.022f);}
 static TextView title(Context c,Pal p,String s){return text(c,s,30,700,p.text,-0.02f);}
 static TextView headline(Context c,Pal p,String s){return text(c,s,17,650,p.text,-0.008f);}
 static TextView body(Context c,Pal p,String s){return text(c,s,15.5f,500,p.muted,0f);}
 static TextView caption(Context c,Pal p,String s){return text(c,s,13,560,p.faint,0.006f);}
 static TextView section(Context c,Pal p,String s){TextView t=text(c,s.toUpperCase(java.util.Locale.ROOT),11.5f,750,p.faint,0.1f);return t;}

 // ---------- shapes ----------
 /** Soft surface: rounded fill with a wide, faint, offset shadow drawn by Paint (renders the same in software and on device). */
 static final class Soft extends Drawable {final Paint f=new Paint(Paint.ANTI_ALIAS_FLAG);final RectF r=new RectF();final float rad;final int stroke;final Paint s=new Paint(Paint.ANTI_ALIAS_FLAG);
  Soft(int fill,float radius,int shadowColor,float blur,float dy,int strokeColor,float strokeW){rad=radius;f.setColor(fill);if(shadowColor!=0&&blur>0)f.setShadowLayer(blur,0,dy,shadowColor);stroke=strokeColor;s.setStyle(Paint.Style.STROKE);s.setStrokeWidth(strokeW);s.setColor(strokeColor);}
  @Override public void draw(Canvas c){r.set(getBounds());c.drawRoundRect(r,rad,rad,f);if(stroke!=0){r.inset(s.getStrokeWidth()/2,s.getStrokeWidth()/2);c.drawRoundRect(r,rad,rad,s);}}
  @Override public void getOutline(Outline o){o.setRoundRect(getBounds(),rad);o.setAlpha(0f);}
  @Override public void setAlpha(int a){f.setAlpha(a);}@Override public void setColorFilter(ColorFilter cf){f.setColorFilter(cf);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}}
 static Drawable soft(Context c,Pal p,int fill,float radiusDp,float lift){return new Soft(fill,dp(c,radiusDp),p.dark?0:p.shadow,dp(c,18*lift),dp(c,6*lift),p.dark?0x0affffff:0,p.dark?Math.max(1,density(c)*0.75f):0);}
 static GradientDrawable round(int fill,float radiusPx,int strokeColor,int strokePx){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(radiusPx);if(strokePx>0)g.setStroke(strokePx,strokeColor);return g;}
 static Drawable ripple(Pal p,Drawable content,float radiusPx){GradientDrawable mask=new GradientDrawable();mask.setColor(0xffffffff);mask.setCornerRadius(radiusPx);return new RippleDrawable(ColorStateList.valueOf(p.ripple),content,mask);}
 static int hair(Context c){return Math.max(1,Math.round(density(c)*0.75f));}
 /** Card: soft surface, 28dp radius, diffuse shadow (light) or gentle lift (dark), 22dp padding. */
 static LinearLayout card(Context c,Pal p){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);l.setBackground(soft(c,p,p.surface,28,1f));int pad=dp(c,22);l.setPadding(pad,pad,pad,pad);return l;}
 static Drawable icon(Context c,int res,int color){Drawable d=c.getDrawable(res).mutate();d.setTint(color);return d;}
 static ImageView iconView(Context c,int res,int color,int sizeDp){ImageView v=new ImageView(c);v.setImageDrawable(icon(c,res,color));v.setLayoutParams(new LinearLayout.LayoutParams(dp(c,sizeDp),dp(c,sizeDp)));return v;}

 // ---------- buttons ----------
 /** Full-width pill. primary = filled; otherwise tonal surface2. Ripple + light haptic. */
 static TextView pill(Context c,Pal p,String label,boolean primary,View.OnClickListener on){TextView b=text(c,label,16.5f,700,primary?p.onPrimary:p.text,0f);b.setGravity(Gravity.CENTER);
  float r=dp(c,30);b.setBackground(ripple(p,primary?soft(c,p,p.primary,30,0.9f):soft(c,p,p.surface,30,0.55f),r));b.setMinHeight(dp(c,58));b.setPadding(dp(c,24),0,dp(c,24),0);b.setClickable(true);b.setFocusable(true);
  b.setOnClickListener(v->{v.performHapticFeedback(android.os.Build.VERSION.SDK_INT>=30?HapticFeedbackConstants.CONFIRM:HapticFeedbackConstants.VIRTUAL_KEY);if(on!=null)on.onClick(v);});
  b.setStateListAnimator(null);b.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN)v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start();else if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL)v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();return false;});return b;}
 static TextView textButton(Context c,Pal p,String label,View.OnClickListener on){TextView b=text(c,label,15.5f,650,p.muted,0f);b.setGravity(Gravity.CENTER);b.setPadding(dp(c,16),dp(c,16),dp(c,16),dp(c,16));float r=dp(c,20);b.setBackground(ripple(p,null,r));b.setClickable(true);b.setOnClickListener(on);return b;}
 static FrameLayout circleButton(Context c,Pal p,int res,View.OnClickListener on){FrameLayout f=new FrameLayout(c);int s=dp(c,44);f.setLayoutParams(new LinearLayout.LayoutParams(s,s));
  f.setBackground(ripple(p,soft(c,p,p.surface,22,0.6f),dp(c,22)));
  ImageView i=new ImageView(c);i.setImageDrawable(icon(c,res,p.text));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(c,22),dp(c,22),Gravity.CENTER);f.addView(i,lp);f.setClickable(true);f.setOnClickListener(on);return f;}

 // ---------- inputs ----------
 static EditText field(Context c,Pal p,String hint,String value,int type){EditText e=new EditText(c);e.setHint(hint);e.setText(value);e.setInputType(type);e.setSingleLine(true);e.setTextSize(TypedValue.COMPLEX_UNIT_SP,16.5f);e.setTypeface(face(c,600,20));
  e.setTextColor(p.text);e.setHintTextColor(p.faint);e.setBackground(round(p.surface2,dp(c,22),0,0));e.setPadding(dp(c,20),0,dp(c,20),0);e.setMinHeight(dp(c,58));
  try{if(android.os.Build.VERSION.SDK_INT>=29){e.setTextCursorDrawable(round(p.text,dp(c,1),0,0));GradientDrawable cur=(GradientDrawable)e.getTextCursorDrawable();cur.setSize(dp(c,2),dp(c,22));}}catch(Throwable ignored){}
  e.setHighlightColor(p.dark?0x55ffffff:0x33000000);return e;}
 static TextView label(Context c,Pal p,String s){TextView t=text(c,s,13.5f,650,p.muted,0.005f);t.setPadding(dp(c,6),0,0,dp(c,10));return t;}

 // ---------- list rows (drawer, settings) ----------
 static LinearLayout row(Context c,Pal p,int iconRes,String title,String value,boolean chevron,View.OnClickListener on){LinearLayout r=new LinearLayout(c);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);
  r.setMinimumHeight(dp(c,58));r.setPadding(dp(c,16),dp(c,4),dp(c,14),dp(c,4));float rad=dp(c,22);r.setBackground(ripple(p,null,rad));
  if(iconRes!=0){FrameLayout tile=new FrameLayout(c);tile.setBackground(round(p.surface2,dp(c,14),0,0));ImageView i=new ImageView(c);i.setImageDrawable(icon(c,iconRes,p.text));tile.addView(i,new FrameLayout.LayoutParams(dp(c,20),dp(c,20),Gravity.CENTER));
   LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(dp(c,40),dp(c,40));tl.rightMargin=dp(c,14);r.addView(tile,tl);}
  TextView t=text(c,title,16.5f,600,p.text,0f);r.addView(t,new LinearLayout.LayoutParams(0,-2,1));
  if(value!=null){TextView v=text(c,value,14.5f,600,p.faint,0);v.setTag("value");r.addView(v);}
  if(chevron){ImageView ch=iconView(c,R.drawable.ic_chevron_right,p.faint,20);((LinearLayout.LayoutParams)ch.getLayoutParams()).leftMargin=dp(c,6);r.addView(ch);}
  if(on!=null){r.setClickable(true);r.setOnClickListener(on);}return r;}
 static View divider(Context c,Pal p,int insetDp){View v=new View(c);v.setBackgroundColor(p.dark?0x12ffffff:0x0f000000);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,hair(c));lp.leftMargin=dp(c,insetDp);v.setLayoutParams(lp);return v;}
 static LinearLayout.LayoutParams lp(int w,int h,int topDp,Context c){LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(w,h);l.topMargin=dp(c,topDp);return l;}
}
