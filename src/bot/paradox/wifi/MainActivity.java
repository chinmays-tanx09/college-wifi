package bot.paradox.wifi;
import android.app.*;import android.os.*;import android.content.*;import android.content.pm.PackageManager;import android.graphics.*;import android.graphics.drawable.*;import android.net.*;import android.provider.Settings;import android.view.*;import android.view.animation.PathInterpolator;import android.widget.*;import android.text.InputType;
/**
 * v4 UI: one calm screen (hero status, one action) and a side drawer for everything else.
 * All views are built in code on the Ui design system; no AndroidX. No timers: the screen redraws when the
 * sign-in engine writes a new status (SharedPreferences listener), and the hero animates only while busy.
 */
public final class MainActivity extends Activity {
 /** Set by the JVM screenshot renderer: build the UI from saved state without starting services or permission prompts. */
 static boolean PREVIEW=false;
 static final PathInterpolator EASE=new PathInterpolator(0.2f,0f,0f,1f);
 Ui.Pal p;FrameLayout root,pageHost;LinearLayout mainCol,drawer;View scrim;OrbView orb;TextView statusT,subT,chipT,autoT,primary;LinearLayout chip;
 String page;boolean drawerOpen,pendingSetup,resumed;int insetTop,insetBottom;String shownKind;long busySince;static final long CANCEL_AFTER_MS=6000;final Runnable cancelR=()->refresh(true);
 EditText ssid,user,pass;
 LinearLayout usageCard;UsageViews.Ring usageRing;UsageViews.Bars usageBars;TextView usageLine,usageSub,usageFix;UsageTracker.View lastUsage;boolean capCustom;
 ScrollView drawerScroll;FrameLayout sheetHost;View sheetScrim;LinearLayout sheet;boolean sheetOpen;
 final SharedPreferences.OnSharedPreferenceChangeListener prefL=(sp,key)->{if(key==null||key.startsWith("status")||key.startsWith("last_")||key.startsWith("upd_")||key.startsWith("active_")||key.equals("suppressed_net")||key.equals("enabled"))runOnUiThread(()->refresh(true));else if(key.equals("u_rev"))runOnUiThread(()->bindUsage("usage".equals(page)));};

 @Override protected void onCreate(Bundle b){super.onCreate(b);p=new Ui.Pal(Ui.isDark(this));setupWindow();if(!PREVIEW&&!Integrity.ok(this)){tampered();return;}build();
  if(b!=null){if(b.getString("page")!=null)showPage(b.getString("page"),false);if(b.getBoolean("drawer"))openDrawer(false);}else if("usage".equals(getIntent().getStringExtra("page")))showPage("usage",false);}
 @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);if("usage".equals(i.getStringExtra("page"))&&root!=null){if(drawerOpen)closeDrawer(false);showPage("usage",false);}}
 @Override protected void onSaveInstanceState(Bundle o){super.onSaveInstanceState(o);o.putString("page",page);o.putBoolean("drawer",drawerOpen);}

 // ---------- window: edge-to-edge, bars match the theme ----------
 void setupWindow(){Window w=getWindow();w.setDecorFitsSystemWindows(false);w.setStatusBarColor(Color.TRANSPARENT);w.setNavigationBarColor(Color.TRANSPARENT);
  if(Build.VERSION.SDK_INT>=29){w.setNavigationBarContrastEnforced(false);w.setStatusBarContrastEnforced(false);}w.setBackgroundDrawable(new ColorDrawable(p.bg));
  if(Build.VERSION.SDK_INT>=28){WindowManager.LayoutParams a=w.getAttributes();a.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;w.setAttributes(a);}
  w.getDecorView().post(()->{WindowInsetsController c=w.getInsetsController();if(c!=null){int light=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;c.setSystemBarsAppearance(p.dark?0:light,light);}});}

 // ---------- main screen ----------
 void build(){root=new FrameLayout(this);root.setBackgroundColor(p.bg);root.setClipChildren(false);
  mainCol=new LinearLayout(this);mainCol.setOrientation(LinearLayout.VERTICAL);mainCol.setClipChildren(false);mainCol.setClipToPadding(false);int side=Ui.dp(this,24);mainCol.setPadding(side,0,side,0);root.addView(mainCol,new FrameLayout.LayoutParams(-1,-1));
  // top bar
  LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(0,Ui.dp(this,8),0,0);bar.setClipChildren(false);bar.setClipToPadding(false);
  FrameLayout menu=Ui.circleButton(this,p,R.drawable.ic_menu,v->openDrawer(true));menu.setContentDescription("Menu");bar.addView(menu);
  TextView word=Ui.text(this,"College WiFi",16,750,p.text,0f);word.setGravity(Gravity.CENTER);bar.addView(word,new LinearLayout.LayoutParams(0,-2,1));
  View balance=new View(this);bar.addView(balance,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,44)));
  mainCol.addView(bar,new LinearLayout.LayoutParams(-1,-2));
  // hero
  LinearLayout hero=new LinearLayout(this){@Override protected void onMeasure(int w,int h){ // v5.2: the orb gives up height so the usage card fits on short screens
   int full=Ui.dp(getContext(),280);orb.cap=full;if(MeasureSpec.getMode(h)==MeasureSpec.UNSPECIFIED){super.onMeasure(w,h);return;}super.onMeasure(w,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));int need=getMeasuredHeight(),avail=MeasureSpec.getSize(h);
   if(need>avail)orb.cap=Math.max(Ui.dp(getContext(),96),Math.min(full,orb.getMeasuredHeight())-(need-avail));super.onMeasure(w,h);}};hero.setOrientation(LinearLayout.VERTICAL);hero.setGravity(Gravity.CENTER);hero.setClipChildren(false);
  orb=new OrbView(this,p);hero.addView(orb,new LinearLayout.LayoutParams(-2,-2));
  statusT=Ui.display(this,p,"");statusT.setGravity(Gravity.CENTER);hero.addView(statusT,Ui.lp(-2,-2,40,this));
  subT=Ui.text(this,"",15.5f,550,p.muted,0f);subT.setGravity(Gravity.CENTER);subT.setMaxLines(3);hero.addView(subT,Ui.lp(-2,-2,12,this));
  chip=new LinearLayout(this);chip.setGravity(Gravity.CENTER_VERTICAL);chip.setBackground(Ui.soft(this,p,p.surface,100,0.45f));chip.setPadding(Ui.dp(this,12),Ui.dp(this,8),Ui.dp(this,14),Ui.dp(this,8));
  ImageView ci=Ui.iconView(this,R.drawable.ic_wifi,p.muted,15);((LinearLayout.LayoutParams)ci.getLayoutParams()).rightMargin=Ui.dp(this,8);chip.addView(ci);chipT=Ui.text(this,"",13,520,p.muted,0.005f);chip.addView(chipT);
  hero.addView(chip,Ui.lp(-2,-2,24,this));
  mainCol.addView(hero,new LinearLayout.LayoutParams(-1,0,1));
  buildUsageCard();
  // action
  updateCard=new LinearLayout(this);updateCard.setOrientation(LinearLayout.VERTICAL);updateCard.setBackground(Ui.soft(this,p,p.surface,26,0.8f));updateCard.setPadding(Ui.dp(this,16),Ui.dp(this,14),Ui.dp(this,16),Ui.dp(this,14));updateCard.setVisibility(View.GONE);mainCol.addView(updateCard,new LinearLayout.LayoutParams(-1,-2){{bottomMargin=Ui.dp(MainActivity.this,14);}});
  primary=Ui.pill(this,p,"",true,v->primaryAction());mainCol.addView(primary,new LinearLayout.LayoutParams(-1,-2));
  autoT=Ui.caption(this,p,"");autoT.setGravity(Gravity.CENTER);mainCol.addView(autoT,Ui.lp(-1,-2,16,this));
  // overlays
  pageHost=new FrameLayout(this);pageHost.setClipChildren(false);pageHost.setVisibility(View.GONE);pageHost.setBackgroundColor(p.bg);pageHost.setClickable(true);root.addView(pageHost,new FrameLayout.LayoutParams(-1,-1));
  scrim=new View(this);scrim.setBackgroundColor(p.scrim);scrim.setAlpha(0f);scrim.setVisibility(View.GONE);scrim.setOnClickListener(v->closeDrawer(true));root.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
  drawer=new LinearLayout(this);drawer.setOrientation(LinearLayout.VERTICAL);drawer.setVisibility(View.GONE);drawer.setClickable(true);FrameLayout.LayoutParams dl=new FrameLayout.LayoutParams(drawerWidth()+Ui.dp(this,32),-1,Gravity.START);dl.leftMargin=-Ui.dp(this,32);root.addView(drawer,dl);
  sheetScrim=new View(this);sheetScrim.setBackgroundColor(p.scrim);sheetScrim.setAlpha(0f);sheetScrim.setVisibility(View.GONE);sheetScrim.setOnClickListener(v->closeSheet(true));root.addView(sheetScrim,new FrameLayout.LayoutParams(-1,-1));
  sheetHost=new FrameLayout(this);sheetHost.setVisibility(View.GONE);root.addView(sheetHost,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
  root.setOnApplyWindowInsetsListener((v,in)->{android.graphics.Insets bars=in.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets ime=in.getInsets(WindowInsets.Type.ime());
   insetTop=bars.top;insetBottom=Math.max(bars.bottom,ime.bottom);mainCol.setPadding(Ui.dp(this,24),insetTop,Ui.dp(this,24),bars.bottom+Ui.dp(this,20));
   drawer.setPadding(Ui.dp(this,32),insetTop,0,bars.bottom);if(pageHost.getChildCount()>0)pageHost.getChildAt(0).setPadding(0,insetTop,0,insetBottom);return in;});
  setContentView(root);buildDrawer();refresh(false);}
 int drawerWidth(){return Math.min(Math.round(getResources().getDisplayMetrics().widthPixels*0.84f),Ui.dp(this,336));}

 /** State shown on the hero, derived from what the sign-in engine last wrote. */
 String kind(){String st=Store.status(this);String k=Session.kind(st);
  if(!Store.has(this))return "SETUP";
  if(!Store.enabled(this)&&!k.equals("CONNECTED")&&!k.startsWith("SIGNING"))k="OFF";
  if(!PREVIEW&&!k.startsWith("SIGNING")&&!k.equals("SETUP")&&away())return "AWAY";
  return k;}
 boolean away(){try{ConnectivityManager cm=Net.cm(this);boolean anyWifi=false;for(Network n:cm.getAllNetworks()){NetworkCapabilities c=cm.getNetworkCapabilities(n);if(c!=null&&c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){anyWifi=true;break;}}
  if(!anyWifi)return true;boolean loc=checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")==PackageManager.PERMISSION_GRANTED&&Net.locationOn(this);
  return loc&&Brain.get(this).network==null&&Net.matching(this)==null;}catch(Exception e){return false;}}
 void refresh(boolean animate){if(statusT==null)return;String k=kind();SharedPreferences sp=Store.p(this);String st=Store.status(this);String ssidName=Store.ssid(this);
  java.text.DateFormat tf=android.text.format.DateFormat.getTimeFormat(this);String title,sub,action;boolean enabled=true;
  switch(k){
   case "CONNECTED":{title="Connected";long ms=sp.getLong("last_signin_ms",-1),t=sp.getLong("last_signin_time",0),ct=sp.getLong("last_check_time",0);
    String timing=ms>=0&&t>0?"Signed in in "+String.format(java.util.Locale.US,"%.1f",ms/1000.0)+" s · "+tf.format(new java.util.Date(t)):ct>0?"Signed in · checked "+tf.format(new java.util.Date(ct)):"Signed in";
    int tot=sp.getInt("active_total",1),ix=sp.getInt("active_index",1);String au=sp.getString("active_user",null);
    sub=tot>1&&au!=null?"Signed in as "+au+" · account "+ix+" of "+tot+"\n"+timing.replace("Signed in in ","in "):timing;action="Sign out";break;}
   case "SIGNING_IN":title="Signing in";sub=st.contains("waiting for the portal")?"Waiting for the college portal…":st.startsWith("Signing back")?"Signing you back in…":st.startsWith("Signing in with the next")?"Switching to your next account…":"Talking to the college portal…";action="Signing in…";enabled=false;break;
   case "SIGNING_OUT":title="Signing out";sub="Telling the portal you're leaving…";action="Signing out…";enabled=false;break;
   case "SIGNED_OUT":title="Signed out";sub="Auto sign-in paused until Wi-Fi reconnects";action="Sign in now";break;
   case "AWAY":title="Not on college Wi-Fi";sub="Connect to "+ssidName+" and it signs in by itself";action="Open Wi-Fi settings";break;
   case "ATTENTION":title="Not signed in";sub=friendly(st);action="Sign in now";break;
   case "SETUP":title="Welcome";sub="Connect to your college Wi-Fi once. It finds the login page and signs in by itself after that.";action="Set up Wi-Fi";break;
   case "OFF":title="Auto sign-in off";sub="Tap below to sign in, or turn it on in the menu";action="Sign in now";break;
   default:title="Ready";sub="Signs in as soon as "+ssidName+" asks";action="Sign in now";}
  // v5.1: a busy screen offers Cancel after a few seconds (one-shot timer, no polling); the engine itself gives up by 15 s anyway
  boolean busy=k.startsWith("SIGNING");if(busy&&(shownKind==null||!shownKind.startsWith("SIGNING")))busySince=android.os.SystemClock.elapsedRealtime();
  root.removeCallbacks(cancelR);
  if(busy&&!PREVIEW){long waited=android.os.SystemClock.elapsedRealtime()-busySince;
   if(waited>=CANCEL_AFTER_MS){action="Cancel";enabled=true;sub=sub+"\nTaking longer than usual · tap Cancel to stop";}else root.postDelayed(cancelR,CANCEL_AFTER_MS-waited+50);}
  boolean changed=!k.equals(shownKind);shownKind=k;orb.setState(k,animate);
  if(changed&&animate){fadeSwap(statusT,title);fadeSwap(subT,sub);}else{statusT.setText(title);subT.setText(sub);}
  primary.setText(action);primary.setEnabled(enabled);primary.setAlpha(enabled?1f:0.7f);
  boolean calm=k.equals("CONNECTED")||!enabled||busy;float pr=Ui.dp(this,30);primary.setBackground(Ui.ripple(p,calm?Ui.soft(this,p,p.surface,30,0.55f):Ui.soft(this,p,p.primary,30,0.9f),pr));primary.setTextColor(calm?p.text:p.onPrimary);
  chipT.setText(ssidName);chip.setVisibility(k.equals("SETUP")?View.GONE:View.VISIBLE);
  autoT.setText(k.equals("SETUP")?"Your login stays encrypted on this phone":!Store.enabled(this)?"Auto sign-in is off":sp.getString("suppressed_net",null)!=null?"Auto sign-in paused · you signed out":"Auto sign-in on · reacts when the portal appears");
  bindUpdateCard();
  if(drawer!=null&&drawer.getChildCount()>0&&!drawerOpen)buildDrawer();}
 static String friendly(String st){if(st.startsWith("Portal: "))return "Portal says: "+st.substring(8);if(st.contains("refused"))return "The portal refused the saved login · check it in Account";
  if(st.startsWith("Couldn't reach the college portal yet"))return "Couldn't reach the portal yet · retrying";if(st.startsWith("Couldn't"))return st.replace(" · "," \n");if(st.startsWith("Sign-out didn't go through"))return "Sign-out didn't go through · still signed in";if(st.startsWith("Sign-out")||st.startsWith("Sign-in")||st.startsWith("Data limit")||st.startsWith("Interrupted"))return st.replace(" · ","\n");return st;}
 void fadeSwap(TextView t,String s){t.animate().cancel();t.animate().alpha(0f).translationY(Ui.dp(this,-4)).setDuration(120).withEndAction(()->{t.setText(s);t.setTranslationY(Ui.dp(this,6));t.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(EASE).start();}).start();}

 void primaryAction(){String k=kind();Brain br=Brain.get(this);
  switch(k){case "SETUP":setupStep=0;draft=null;showPage("setup",true);return;
   case "CONNECTED":br.logout();refresh(true);return;
   case "SIGNING_IN":case "SIGNING_OUT":br.cancelBusy(false);refresh(true);return;
   case "AWAY":try{startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}catch(Exception e){toast("Open Wi-Fi settings and connect to "+Store.ssid(this));}return;
   default:br.signInNow();refresh(true);}}

 /** Fail closed: a re-signed or modified copy never touches saved logins or the network. */
 void tampered(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setGravity(Gravity.CENTER);c.setPadding(Ui.dp(this,32),0,Ui.dp(this,32),0);c.setBackgroundColor(p.bg);
  c.addView(Ui.iconView(this,R.drawable.ic_shield,p.text,40));TextView t=Ui.title(this,p,"This copy was modified");t.setGravity(Gravity.CENTER);c.addView(t,Ui.lp(-2,-2,20,this));
  TextView s=Ui.body(this,p,"College WiFi only runs when it's signed by its developer. Install the official app from github.com/chinmays-tanx09/paradox-wifi.");s.setGravity(Gravity.CENTER);c.addView(s,Ui.lp(-1,-2,10,this));setContentView(c);
  try{stopService(new Intent(this,MonitorService.class));Triggers.unregister(this);}catch(Exception ignored){}}
 // ---------- drawer ----------
 void buildDrawer(){drawer.removeAllViews();drawer.setBackground(new Ui.Soft(p.surface,Ui.dp(this,32),p.dark?0x99000000:0x2a1c1c24,Ui.dp(this,40),0,0,0));
  drawer.setPadding(Ui.dp(this,32),insetTop,0,insetBottom);
  LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(Ui.dp(this,24),Ui.dp(this,28),Ui.dp(this,20),Ui.dp(this,20));
  FrameLayout mark=new FrameLayout(this);GradientDrawable mg=new GradientDrawable();mg.setShape(GradientDrawable.OVAL);mg.setColor(p.primary);mark.setBackground(mg);ImageView mi=new ImageView(this);mi.setImageDrawable(Ui.icon(this,R.drawable.ic_wifi,p.onPrimary));mark.addView(mi,new FrameLayout.LayoutParams(Ui.dp(this,22),Ui.dp(this,22),Gravity.CENTER));
  head.addView(mark,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,44)));
  LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(Ui.dp(this,14),0,0,0);names.addView(Ui.text(this,"College WiFi",19,750,p.text,-0.01f));TextView ver=Ui.caption(this,p,"Version 5.3");names.addView(ver,Ui.lp(-2,-2,4,this));head.addView(names,new LinearLayout.LayoutParams(0,-2,1));
  drawer.addView(head);
  ScrollView sv=new ScrollView(this);drawerScroll=sv;sv.setVerticalScrollBarEnabled(false);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(Ui.dp(this,10),Ui.dp(this,4),Ui.dp(this,12),Ui.dp(this,16));sv.addView(list);drawer.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
  list.addView(sectionLabel("Connection"));
  Store.NetProfile cur=Store.current(this);int na=cur==null?0:cur.accounts.size(),nn=Store.nets(this).size();
  list.addView(Ui.row(this,p,R.drawable.ic_person,"Accounts",na==0?"Not set":String.valueOf(na),true,v->navigate(cur==null?"setup":"accounts")));
  list.addView(Ui.row(this,p,R.drawable.ic_wifi,"Networks",nn==0?"Add":String.valueOf(nn),true,v->navigate(nn==0?"setup":"networks")));
  LinearLayout auto=Ui.row(this,p,R.drawable.ic_bolt,"Auto sign-in",null,false,null);Toggle tg=new Toggle(this,p,Store.enabled(this),this::setAuto);auto.addView(tg);auto.setOnClickListener(v->tg.performClick());auto.setClickable(true);list.addView(auto);
  list.addView(sectionLabel("Appearance"));
  list.addView(Ui.row(this,p,R.drawable.ic_data_usage,"Data usage",UsageTracker.lastLine==null?null:shortUsage(),true,v->navigate("usage")));
  list.addView(Ui.row(this,p,R.drawable.ic_contrast,"Theme",themeName(Ui.themePref(this)),true,v->navigate("theme")));
  list.addView(sectionLabel("Support"));
  list.addView(Ui.row(this,p,R.drawable.ic_monitor_heart,"Diagnostics",null,true,v->navigate("diag")));
  list.addView(Ui.row(this,p,R.drawable.ic_info,"About",null,true,v->navigate("about")));
  list.addView(sectionLabel("Developer"));list.addView(devCard(),devLp());
  TextView foot=Ui.caption(this,p,"No background polling.\nSigns in when Android sees the portal.");foot.setPadding(Ui.dp(this,24),Ui.dp(this,8),Ui.dp(this,24),Ui.dp(this,24));drawer.addView(foot);}
 TextView sectionLabel(String s){TextView t=Ui.section(this,p,s);t.setPadding(Ui.dp(this,16),Ui.dp(this,22),0,Ui.dp(this,8));return t;}
 static String themeName(String t){return "dark".equals(t)?"Dark":"light".equals(t)?"Light":"System";}
 void navigate(String pg){closeDrawer(true);root.postDelayed(()->showPage(pg,true),PREVIEW?0:140);}
 void openDrawer(boolean anim){if(drawerOpen)return;drawerOpen=true;buildDrawer();drawer.setVisibility(View.VISIBLE);scrim.setVisibility(View.VISIBLE);int w=drawerWidth()+Ui.dp(this,72);
  if(!anim){drawer.setTranslationX(0);scrim.setAlpha(1f);return;}drawer.setTranslationX(-w);drawer.animate().translationX(0).setDuration(320).setInterpolator(EASE).start();scrim.animate().alpha(1f).setDuration(260).start();
  mainCol.animate().scaleX(0.97f).scaleY(0.97f).setDuration(320).setInterpolator(EASE).start();}
 void closeDrawer(boolean anim){if(!drawerOpen)return;drawerOpen=false;int w=drawerWidth()+Ui.dp(this,72);mainCol.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(EASE).start();
  if(!anim){drawer.setVisibility(View.GONE);scrim.setVisibility(View.GONE);scrim.setAlpha(0f);return;}
  drawer.animate().translationX(-w).setDuration(260).setInterpolator(EASE).withEndAction(()->drawer.setVisibility(View.GONE)).start();scrim.animate().alpha(0f).setDuration(220).withEndAction(()->scrim.setVisibility(View.GONE)).start();}
 void setAuto(boolean on){if(on){if(!Store.has(this)){toast("Add your login first");buildDrawer();return;}Store.enable(this,true);Store.p(this).edit().remove("suppressed_net").apply();if(!PREVIEW){Triggers.register(this);ensureService();}Store.status(this,"Watching "+Store.ssid(this));}
  else{Store.enable(this,false);if(!PREVIEW){stopService(new Intent(this,MonitorService.class));Triggers.unregister(this);}Store.status(this,"Auto-login off");}refresh(true);}

 // ---------- pages ----------
 void showPage(String pg,boolean anim){page=pg;pageHost.removeAllViews();
  ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setClipToPadding(false);sv.setVerticalScrollBarEnabled(false);sv.setPadding(0,insetTop,0,insetBottom);
  LinearLayout col=new LinearLayout(this);col.setOrientation(LinearLayout.VERTICAL);col.setClipChildren(false);col.setClipToPadding(false);col.setPadding(Ui.dp(this,24),Ui.dp(this,8),Ui.dp(this,24),Ui.dp(this,40));sv.addView(col);
  LinearLayout bar=new LinearLayout(this);bar.setClipChildren(false);bar.addView(Ui.circleButton(this,p,R.drawable.ic_arrow_back,v->closePage(true)));col.addView(bar);
  String t=pageTitle(pg);
  col.addView(Ui.title(this,p,t),Ui.lp(-2,-2,28,this));
  switch(pg){case "accounts":pageAccounts(col);break;case "account_edit":pageAccountEdit(col);break;case "networks":pageNetworks(col);break;case "network":pageNetwork(col);break;case "setup":pageSetup(col);break;
   case "theme":pageTheme(col);break;case "usage":pageUsage(col);break;case "diag":pageDiag(col);break;default:pageAbout(col);}
  pageHost.addView(sv,new FrameLayout.LayoutParams(-1,-1));pageHost.setVisibility(View.VISIBLE);
  if(anim){int w=getResources().getDisplayMetrics().widthPixels;pageHost.setTranslationX(w*0.25f);pageHost.setAlpha(0f);pageHost.animate().translationX(0).alpha(1f).setDuration(300).setInterpolator(EASE).start();}else{pageHost.setTranslationX(0);pageHost.setAlpha(1f);}}
 void closePage(boolean anim){if("account_edit".equals(page)||"network".equals(page)){String back="account_edit".equals(page)?"accounts":"networks";page=null;hideKeyboard();user=pass=null;showPage(back,false);return;}page=null;hideKeyboard();ssid=user=pass=null;if(!anim){pageHost.setVisibility(View.GONE);pageHost.removeAllViews();return;}
  pageHost.animate().translationX(getResources().getDisplayMetrics().widthPixels*0.25f).alpha(0f).setDuration(220).setInterpolator(EASE).withEndAction(()->{pageHost.setVisibility(View.GONE);pageHost.removeAllViews();}).start();refresh(true);}
 void lead(LinearLayout col,String s){TextView b=Ui.body(this,p,s);col.addView(b,Ui.lp(-1,-2,10,this));}

 static String pageTitle(String pg){switch(pg){case "usage":return "Data usage";case "accounts":return "Accounts";case "account_edit":return "Account";case "networks":return "Networks";case "network":return "Network";case "setup":return "Set up Wi-Fi";case "theme":return "Theme";case "diag":return "Diagnostics";default:return "About";}}
 // ---------- v5: accounts (several logins per network, automatic failover) ----------
 String editId,netSsid;
 Store.NetProfile pageNet(){Store.NetProfile np=netSsid==null?null:Store.net(this,netSsid);return np!=null?np:Store.current(this);}
 void pageAccounts(LinearLayout col){Store.NetProfile np=pageNet();
  if(np==null){lead(col,"No college Wi-Fi saved yet. Set one up first, then add as many logins as you like.");col.addView(Ui.pill(this,p,"Set up Wi-Fi",true,v->showPage("setup",true)),Ui.lp(-1,-2,28,this));return;}
  lead(col,np.ssid()+" · "+np.portalName()+". If an account hits its login, device or data limit, the next one signs in automatically.");
  String activeId=Store.p(this).getString("active_id","");long now=System.currentTimeMillis();java.text.DateFormat tf=android.text.format.DateFormat.getTimeFormat(this);
  final LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setClipChildren(false);col.addView(list,Ui.lp(-1,-2,24,this));
  for(int i=0;i<np.accounts.size();i++){final Accounts.Account a=np.accounts.get(i);LinearLayout card=accountCard(np,a,i,a.id.equals(activeId)&&Session.kind(Store.status(this)).equals("CONNECTED"),now,tf,list);list.addView(card,Ui.lp(-1,-2,i==0?0:10,this));}
  LinearLayout add=new LinearLayout(this);add.setGravity(Gravity.CENTER);add.setMinimumHeight(Ui.dp(this,56));add.setBackground(Ui.ripple(p,Ui.soft(this,p,p.primary,28,0.9f),Ui.dp(this,28)));add.setClickable(true);
  ImageView ai=Ui.iconView(this,R.drawable.ic_add,p.onPrimary,22);((LinearLayout.LayoutParams)ai.getLayoutParams()).rightMargin=Ui.dp(this,8);add.addView(ai);add.addView(Ui.text(this,"Add account",16.5f,700,p.onPrimary,0f));
  add.setOnClickListener(v->{editId=null;showPage("account_edit",true);});col.addView(add,Ui.lp(-1,-2,22,this));
  col.addView(Ui.caption(this,p,(np.accounts.size()>1?"Drag to reorder. The first one is your primary and is tried first on every connect. ":"")+"A wrong password is tried once and then parked until you fix it, so the portal never locks you out. A data or time limit is retried after midnight."),Ui.lp(-1,-2,18,this));
  col.addView(Ui.caption(this,p,"Every username and password is encrypted on this phone with a key kept in Android Keystore. Passwords never appear in diagnostics or notifications."),Ui.lp(-1,-2,10,this));}
 LinearLayout accountCard(Store.NetProfile np,Accounts.Account a,int i,boolean active,long now,java.text.DateFormat tf,LinearLayout list){
  LinearLayout c=new LinearLayout(this);c.setGravity(Gravity.CENTER_VERTICAL);c.setPadding(Ui.dp(this,8),Ui.dp(this,14),Ui.dp(this,16),Ui.dp(this,14));c.setBackground(Ui.ripple(p,Ui.soft(this,p,p.surface,24,0.8f),Ui.dp(this,24)));c.setClickable(true);c.setTag(a.id);
  ImageView handle=Ui.iconView(this,R.drawable.ic_drag,p.faint,22);LinearLayout.LayoutParams hl=(LinearLayout.LayoutParams)handle.getLayoutParams();hl.rightMargin=Ui.dp(this,6);handle.setPadding(Ui.dp(this,4),Ui.dp(this,8),Ui.dp(this,4),Ui.dp(this,8));hl.width=Ui.dp(this,32);hl.height=Ui.dp(this,40);handle.setContentDescription("Drag to reorder");c.addView(handle);
  TextView av=Ui.text(this,String.valueOf(i+1),15,750,i==0?p.onPrimary:p.text,0f);av.setGravity(Gravity.CENTER);av.setBackground(Ui.round(i==0?p.primary:p.surface2,Ui.dp(this,20),0,0));c.addView(av,new LinearLayout.LayoutParams(Ui.dp(this,40),Ui.dp(this,40)));
  LinearLayout t=new LinearLayout(this);t.setOrientation(LinearLayout.VERTICAL);t.setPadding(Ui.dp(this,14),0,Ui.dp(this,8),0);TextView un=Ui.text(this,a.user,16.5f,700,p.text,0f);un.setSingleLine(true);un.setEllipsize(android.text.TextUtils.TruncateAt.END);t.addView(un);
  String chip=Accounts.chip(a,active,now,tf);boolean pill=active||a.parked();
  t.addView(Ui.text(this,(i==0?"Primary":"Account "+(i+1))+(!pill&&!chip.isEmpty()?" · "+chip:""),13,600,p.faint,0f),Ui.lp(-2,-2,2,this));c.addView(t,new LinearLayout.LayoutParams(0,-2,1));
  if(pill&&!chip.isEmpty()){boolean warn=a.parked();TextView ch=Ui.text(this,chip,12.5f,700,active?p.onPrimary:warn?p.text:p.muted,0f);ch.setPadding(Ui.dp(this,10),Ui.dp(this,5),Ui.dp(this,10),Ui.dp(this,5));
   ch.setBackground(Ui.round(active?p.primary:p.surface2,Ui.dp(this,100),warn?p.text:0,warn?Math.max(1,Ui.dp(this,1)):0));c.addView(ch);}
  c.setOnClickListener(v->{editId=a.id;showPage("account_edit",true);});
  handle.setOnTouchListener(new View.OnTouchListener(){float startY;int from;public boolean onTouch(View h,MotionEvent e){
    switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:startY=e.getRawY();from=list.indexOfChild(c);c.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start();c.setElevation(Ui.dp(MainActivity.this,8));h.getParent().requestDisallowInterceptTouchEvent(true);h.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);return true;
     case MotionEvent.ACTION_MOVE:{float dy=e.getRawY()-startY;c.setTranslationY(dy);int idx=list.indexOfChild(c);int step=c.getHeight()+Ui.dp(MainActivity.this,10);
      if(dy>step*0.6f&&idx<list.getChildCount()-1){View n=list.getChildAt(idx+1);list.removeView(c);list.addView(c,idx+1);startY+=step;c.setTranslationY(e.getRawY()-startY);fixMargins(list);n.setTranslationY(step);n.animate().translationY(0).setDuration(160).start();h.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}
      else if(dy<-step*0.6f&&idx>0){View n=list.getChildAt(idx-1);list.removeView(c);list.addView(c,idx-1);startY-=step;c.setTranslationY(e.getRawY()-startY);fixMargins(list);n.setTranslationY(-step);n.animate().translationY(0).setDuration(160).start();h.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}
      return true;}
     case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:c.animate().translationY(0).scaleX(1f).scaleY(1f).setDuration(160).start();c.setElevation(0);
      if(list.indexOfChild(c)!=from){java.util.List<Accounts.Account> order=new java.util.ArrayList<>();for(int k=0;k<list.getChildCount();k++){String id=(String)list.getChildAt(k).getTag();for(Accounts.Account x:np.accounts)if(x.id.equals(id))order.add(x);}
       np.accounts.clear();np.accounts.addAll(order);Store.saveNet(MainActivity.this,np);Brain.get(MainActivity.this).credentialsChanged();root.postDelayed(()->showPage("accounts",false),180);}return true;}return false;}});
  return c;}
 void fixMargins(LinearLayout list){for(int k=0;k<list.getChildCount();k++){LinearLayout.LayoutParams l=(LinearLayout.LayoutParams)list.getChildAt(k).getLayoutParams();l.topMargin=k==0?0:Ui.dp(this,10);list.getChildAt(k).setLayoutParams(l);}}
 void pageAccountEdit(LinearLayout col){Store.NetProfile np=pageNet();if(np==null){closePage(false);return;}Accounts.Account a=null;for(Accounts.Account x:np.accounts)if(x.id.equals(editId))a=x;final Accounts.Account acc=a;
  lead(col,acc==null?"Another login for "+np.ssid()+". It's used when the ones above it hit a limit.":"Leave the password empty to keep the saved one.");
  if(acc!=null&&!acc.message.isEmpty()&&!acc.state.isEmpty()){LinearLayout warn=Ui.card(this,p);warn.addView(Ui.text(this,Accounts.chip(acc,false,System.currentTimeMillis(),android.text.format.DateFormat.getTimeFormat(this)),13,750,p.text,0f));warn.addView(Ui.text(this,"Portal said: "+acc.message,14,550,p.muted,0f),Ui.lp(-1,-2,4,this));col.addView(warn,Ui.lp(-1,-2,20,this));}
  col.addView(Ui.label(this,p,"Username"),Ui.lp(-1,-2,28,this));user=Ui.field(this,p,"Portal username",acc==null?"":acc.user,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);col.addView(user,new LinearLayout.LayoutParams(-1,-2));
  col.addView(Ui.label(this,p,"Password"),Ui.lp(-1,-2,20,this));pass=Ui.field(this,p,acc==null?"Portal password":"••••••••","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);col.addView(pass,new LinearLayout.LayoutParams(-1,-2));
  col.addView(Ui.pill(this,p,acc==null?"Add account":"Save",true,v->{String u=user.getText().toString().trim(),pw=pass.getText().toString();if(u.isEmpty()||(acc==null&&pw.isEmpty())){toast("Enter the username and password");return;}
   try{if(acc==null)Store.addAccount(this,np,u,pw);else Store.updateAccount(this,np,acc,u,pw);Brain.get(this).credentialsChanged();pass.setText("");hideKeyboard();toast(acc==null?"Account added":"Saved");closePage(true);}catch(Exception e){toast("Could not save securely on this phone");}}),Ui.lp(-1,-2,28,this));
  if(acc!=null){int idx=np.accounts.indexOf(acc);
   if(idx>0)col.addView(Ui.pill(this,p,"Make primary",false,v->{np.accounts.remove(acc);np.accounts.add(0,acc);Store.saveNet(this,np);Brain.get(this).credentialsChanged();toast("Primary account changed");closePage(true);}),Ui.lp(-1,-2,12,this));
   col.addView(Ui.textButton(this,p,"Remove account",v->dialog().setTitle("Remove "+acc.user+"?").setMessage("Its encrypted login is deleted from this phone.").setPositiveButton("Remove",(x,y)->{np.accounts.remove(acc);np.sealed.remove(acc.id);Store.saveNet(this,np);Brain.get(this).credentialsChanged();closePage(true);}).setNegativeButton("Cancel",null).show()),Ui.lp(-1,-2,8,this));}
  col.addView(Ui.caption(this,p,"Encrypted with a key kept in Android Keystore. Never shown again, never in diagnostics."),Ui.lp(-1,-2,20,this));}

 // ---------- v5: saved networks ----------
 void pageNetworks(LinearLayout col){java.util.List<Store.NetProfile> nets=Store.nets(this);lead(col,"Each Wi-Fi remembers its own portal and logins. Your login is only ever sent to the portal confirmed for that network.");
  for(int i=0;i<nets.size();i++){Store.NetProfile np=nets.get(i);LinearLayout c=new LinearLayout(this);c.setGravity(Gravity.CENTER_VERTICAL);c.setPadding(Ui.dp(this,16),Ui.dp(this,16),Ui.dp(this,14),Ui.dp(this,16));c.setBackground(Ui.ripple(p,Ui.soft(this,p,p.surface,24,0.8f),Ui.dp(this,24)));c.setClickable(true);
   FrameLayout tile=new FrameLayout(this);tile.setBackground(Ui.round(p.surface2,Ui.dp(this,14),0,0));ImageView ic=new ImageView(this);ic.setImageDrawable(Ui.icon(this,R.drawable.ic_wifi,p.text));tile.addView(ic,new FrameLayout.LayoutParams(Ui.dp(this,20),Ui.dp(this,20),Gravity.CENTER));c.addView(tile,new LinearLayout.LayoutParams(Ui.dp(this,42),Ui.dp(this,42)));
   LinearLayout t=new LinearLayout(this);t.setOrientation(LinearLayout.VERTICAL);t.setPadding(Ui.dp(this,14),0,Ui.dp(this,8),0);TextView nm=Ui.text(this,np.ssid(),16.5f,700,p.text,0f);nm.setSingleLine(true);nm.setEllipsize(android.text.TextUtils.TruncateAt.END);t.addView(nm);
   t.addView(Ui.text(this,np.portalName()+" · "+np.host()+" · "+np.accounts.size()+(np.accounts.size()==1?" account":" accounts"),13,600,p.faint,0f),Ui.lp(-2,-2,2,this));c.addView(t,new LinearLayout.LayoutParams(0,-2,1));
   c.addView(Ui.iconView(this,R.drawable.ic_chevron_right,p.faint,20));c.setOnClickListener(v->{netSsid=np.ssid();showPage("network",true);});col.addView(c,Ui.lp(-1,-2,i==0?24:10,this));}
  col.addView(Ui.pill(this,p,"Add a network",nets.isEmpty(),v->{draft=null;setupStep=0;showPage("setup",true);}),Ui.lp(-1,-2,22,this));}
 void pageNetwork(LinearLayout col){Store.NetProfile np=netSsid==null?null:Store.net(this,netSsid);if(np==null){closePage(false);return;}
  LinearLayout card=Ui.card(this,p);kv(card,"Wi-Fi",np.ssid(),true);kv(card,"Portal",np.portalName(),true);kv(card,"Login page",np.n.loginUrl,true);kv(card,"Your login is only sent to",android.text.TextUtils.join(", ",np.n.hosts),true);
  kv(card,"Connection",np.plainHttp()?"Plain HTTP: the portal itself doesn't encrypt the login on the network":"HTTPS"+(np.pins.isEmpty()?"":" · certificate pinned on first use"),false);col.addView(card,Ui.lp(-1,-2,8,this));
  col.addView(Ui.pill(this,p,"Accounts ("+np.accounts.size()+")",true,v->{netSsid=np.ssid();showPage("accounts",true);}),Ui.lp(-1,-2,24,this));
  col.addView(Ui.pill(this,p,"Re-detect portal",false,v->{draft=null;setupStep=0;setupFor=np.ssid();showPage("setup",true);startDetect(null);}),Ui.lp(-1,-2,12,this));
  col.addView(Ui.label(this,p,"Manual portal URL"),Ui.lp(-1,-2,28,this));manualUrl=Ui.field(this,p,"e.g. http://10.0.0.1:8090",np.n.r("manual"),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);col.addView(manualUrl,new LinearLayout.LayoutParams(-1,-2));
  col.addView(Ui.pill(this,p,"Use this URL",false,v->{String u=manualUrl.getText().toString().trim();if(!u.matches("(?i)https?://\\S+")){toast("Enter a full http:// or https:// address");return;}hideKeyboard();draft=null;setupStep=0;setupFor=np.ssid();showPage("setup",true);startDetect(u);}),Ui.lp(-1,-2,12,this));
  col.addView(Ui.caption(this,p,"Only needed if detection can't find the login page. The app still checks what answers there before sending anything."),Ui.lp(-1,-2,10,this));
  col.addView(Ui.textButton(this,p,"Remove network",v->dialog().setTitle("Remove "+np.ssid()+"?").setMessage("Its portal settings and every saved login for it are deleted from this phone.").setPositiveButton("Remove",(x,y)->{Store.removeNet(this,np.ssid());Brain.get(this).credentialsChanged();netSsid=null;page=null;showPage("networks",false);}).setNegativeButton("Cancel",null).show()),Ui.lp(-1,-2,16,this));}

 // ---------- v5: setup — connect, portal found, login, test, done ----------
 Brain.Draft draft;int setupStep;String setupNote,setupFor;boolean setupBusy;EditText manualUrl;
 void startDetect(String hint){if(PREVIEW)return;setupBusy=true;setupNote=null;if("setup".equals(page))showPage("setup",false);
  Brain.get(this).detect(hint,setupFor==null?null:Store.net(this,setupFor),d->{setupBusy=false;draft=d;if(d.found!=null&&d.found.loginUrl!=null){setupStep=2;if(d.ssid==null&&setupFor!=null)d.np.n.ssid=setupFor;if(hint!=null)d.np.n.recipe.put("manual",hint);}
   else if(d.network==null)setupNote="Not connected to any Wi-Fi yet.";else if(d.found!=null&&d.found.online)setupNote="This Wi-Fi is already online, so there's no login page to find. Sign out of the portal (or forget and re-join the Wi-Fi), then try again. Or enter the portal URL below.";
   else if(d.certFp!=null)setupStep=1;else setupNote=d.found==null||d.found.error==null?"No login page answered on this Wi-Fi.":d.found.error+".";
   if("setup".equals(page))showPage("setup",false);});}
 LinearLayout step(LinearLayout col,int n,String title,String sub,int state){ // state: 0 todo, 1 active, 2 done
  LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(Ui.dp(this,18),Ui.dp(this,18),Ui.dp(this,18),Ui.dp(this,18));c.setBackground(state==1?Ui.soft(this,p,p.surface,26,1f):Ui.round(state==2?p.surface:p.bg,Ui.dp(this,26),state==0?p.hairline:0,state==0?Math.max(1,Ui.dp(this,1)):0));
  LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);View dot;
  if(state==2){FrameLayout f=new FrameLayout(this);f.setBackground(Ui.round(p.primary,Ui.dp(this,15),0,0));ImageView ck=new ImageView(this);ck.setImageDrawable(Ui.icon(this,R.drawable.ic_check,p.onPrimary));f.addView(ck,new FrameLayout.LayoutParams(Ui.dp(this,18),Ui.dp(this,18),Gravity.CENTER));dot=f;}
  else{TextView d=Ui.text(this,String.valueOf(n),14,750,state==1?p.onPrimary:p.faint,0f);d.setGravity(Gravity.CENTER);d.setBackground(Ui.round(state==1?p.primary:p.surface2,Ui.dp(this,15),0,0));dot=d;}
  head.addView(dot,new LinearLayout.LayoutParams(Ui.dp(this,30),Ui.dp(this,30)));LinearLayout tt=new LinearLayout(this);tt.setOrientation(LinearLayout.VERTICAL);tt.setPadding(Ui.dp(this,14),0,0,0);
  tt.addView(Ui.text(this,title,state==0?15.5f:16.5f,state==0?650:750,state==0?p.faint:p.text,0f));if(sub!=null&&!sub.isEmpty()){TextView st=Ui.text(this,sub,13.5f,550,p.muted,0f);tt.addView(st,Ui.lp(-1,-2,3,this));}
  head.addView(tt,new LinearLayout.LayoutParams(0,-2,1));c.addView(head);col.addView(c,Ui.lp(-1,-2,col.getChildCount()<=2?20:10,this));return c;}
 void pageSetup(LinearLayout col){String wifi=PREVIEW?Store.p(this).getString("wifi_now",null):currentSsid();boolean connected=PREVIEW?wifi!=null:Net.currentWifi(this)!=null;
  lead(col,"Works with most college portals: Sophos, FortiGate, pfSense, OPNsense, MikroTik, Aruba, Cisco and plain login pages.");
  // 1. connect
  LinearLayout s1=step(col,1,"Connect to your college Wi-Fi",setupStep>=2?(draft!=null&&draft.ssid!=null?draft.ssid:wifi):connected?(wifi!=null?"Connected to "+wifi:"Connected · allow Location to read the Wi-Fi name"):"Join it in Wi-Fi settings, then come back",setupStep>=2?2:1);
  if(setupStep<2){if(!connected)s1.addView(Ui.pill(this,p,"Open Wi-Fi settings",false,v->{try{startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));}catch(Exception ignored){}}),Ui.lp(-1,-2,16,this));
   else if(wifi==null&&checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")!=PackageManager.PERMISSION_GRANTED)s1.addView(Ui.pill(this,p,"Allow Location",false,v->requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"},3)),Ui.lp(-1,-2,16,this));
   if(connected){TextView go=Ui.pill(this,p,setupBusy?"Looking for the login page…":"Find the login page",true,v->{if(!setupBusy)startDetect(null);});go.setEnabled(!setupBusy);s1.addView(go,Ui.lp(-1,-2,16,this));}
   if(setupNote!=null){s1.addView(Ui.text(this,setupNote,13.5f,600,p.text,0f),Ui.lp(-1,-2,14,this));
    manualUrl=Ui.field(this,p,"Portal URL, e.g. http://10.0.0.1:8090","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);s1.addView(manualUrl,Ui.lp(-1,-2,12,this));
    s1.addView(Ui.pill(this,p,"Use this URL",false,v->{String u=manualUrl.getText().toString().trim();if(!u.matches("(?i)https?://\\S+")){toast("Enter a full http:// or https:// address");return;}hideKeyboard();startDetect(u);}),Ui.lp(-1,-2,10,this));}
   if(setupStep==1&&draft!=null&&draft.certFp!=null){s1.addView(Ui.text(this,draft.certHost+" uses its own security certificate (common on campus networks). Trust it for this Wi-Fi only? Fingerprint:\n"+draft.certFp,13,600,p.text,0f),Ui.lp(-1,-2,14,this));
    s1.addView(Ui.pill(this,p,"Trust this certificate",false,v->{Store.NetProfile pins=new Store.NetProfile();pins.pins.put(draft.certHost,draft.certFp);pins.n.ssid=draft.ssid==null?"":draft.ssid;Store.NetProfile ex=draft.ssid==null?null:Store.net(this,draft.ssid);
     if(ex!=null){ex.pins.put(draft.certHost,draft.certFp);Store.saveNet(this,ex);}pinDraft=pins;Brain.get(this).detect(null,pins,d->{d.np.pins.putAll(pins.pins);draft=d;setupStep=d.found!=null&&d.found.loginUrl!=null?2:0;if(setupStep==0)setupNote="Still couldn't open the login page.";showPage("setup",false);});}),Ui.lp(-1,-2,12,this));}}
  // 2. portal found
  String found=draft!=null&&draft.found!=null&&draft.found.loginUrl!=null?"Portal found: "+Portal.name(draft.found.type)+" at "+draft.found.host:null;
  LinearLayout s2=step(col,2,found!=null?found:"Find the login page",found!=null?(draft.found.https()?"Secure (HTTPS)":"Plain HTTP portal")+" · login goes only to "+android.text.TextUtils.join(", ",draft.np.n.hosts):"Detected automatically from the Wi-Fi's redirect",setupStep>=3?2:setupStep==2?2:0);
  if(found!=null&&setupStep==2&&!draft.found.https()){LinearLayout n=new LinearLayout(this);n.setGravity(Gravity.TOP);ImageView li=Ui.iconView(this,R.drawable.ic_lock,p.muted,16);((LinearLayout.LayoutParams)li.getLayoutParams()).rightMargin=Ui.dp(this,8);((LinearLayout.LayoutParams)li.getLayoutParams()).topMargin=Ui.dp(this,2);n.addView(li);
   n.addView(Ui.text(this,"This portal uses plain HTTP, so your password crosses the campus network unencrypted. That's how the portal works; on this phone it's still stored encrypted.",12.5f,550,p.muted,0f),new LinearLayout.LayoutParams(0,-2,1));s2.addView(n,Ui.lp(-1,-2,12,this));}
  // 3. login + test
  LinearLayout s3=step(col,3,setupStep>=4?"Signed in":"Your portal login",setupStep>=4?"Test sign-in worked":setupStep>=2?"Test it once now. Add more accounts later.":null,setupStep>=4?2:setupStep>=2?1:0);
  if(setupStep>=2&&setupStep<4){user=Ui.field(this,p,"Portal username","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);s3.addView(user,Ui.lp(-1,-2,16,this));pass=Ui.field(this,p,"Portal password","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);s3.addView(pass,Ui.lp(-1,-2,10,this));
   if(PREVIEW)user.setText(Store.p(this).getString("preview_user",""));
   TextView test=Ui.pill(this,p,setupBusy?"Signing in…":"Test sign-in",true,v->testLogin());test.setEnabled(!setupBusy);s3.addView(test,Ui.lp(-1,-2,16,this));
   if(setupNote!=null&&setupStep==3)s3.addView(Ui.text(this,setupNote,13.5f,600,p.text,0f),Ui.lp(-1,-2,12,this));}
  // 4. done
  LinearLayout s4=step(col,4,"Done","It signs in by itself every time you join this Wi-Fi",setupStep>=4?1:0);
  if(setupStep>=4)s4.addView(Ui.pill(this,p,"Finish",true,v->{setupStep=0;draft=null;setupFor=null;pendingSetup=true;closePage(true);checkPermissions();}),Ui.lp(-1,-2,16,this));}
 Store.NetProfile pinDraft;
 String currentSsid(){Network n=Net.currentWifi(this);return n==null?null:Net.ssid(this,n);}
 void testLogin(){if(PREVIEW||draft==null)return;String u=user.getText().toString().trim(),pw=pass.getText().toString();if(u.isEmpty()||pw.isEmpty()){toast("Enter your portal username and password");return;}
  if(draft.np.n.ssid==null||draft.np.n.ssid.isEmpty()){String s=currentSsid();if(s==null){toast("Allow Location so the app can tell which Wi-Fi this login is for");requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"},3);return;}draft.np.n.ssid=s;}
  draft.np.httpAck=true;setupBusy=true;setupNote=null;hideKeyboard();showPage("setup",false);
  Brain.get(this).testSignIn(draft,u,pw,r->{setupBusy=false;if(r.ok()){setupStep=4;Store.enable(this,true);}else{setupStep=3;setupNote=r.kind==Portal.Kind.WRONG_PASSWORD?"The portal said the username or password is wrong: "+r.message:r.message.isEmpty()?r.kind.toString():r.message;}showPage("setup",false);});
  pw=null;}

 // ---------- v5: update card ----------
 LinearLayout updateCard;TextView updBtn;View updBar;
 void bindUpdateCard(){if(updateCard==null)return;boolean show=Updater.available(this);updateCard.setVisibility(show?View.VISIBLE:View.GONE);if(!show)return;updateCard.removeAllViews();SharedPreferences sp=Store.p(this);boolean mand=sp.getBoolean("upd_mandatory",false);
  LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);FrameLayout tile=new FrameLayout(this);tile.setBackground(Ui.round(p.surface2,Ui.dp(this,14),0,0));ImageView ic=new ImageView(this);ic.setImageDrawable(Ui.icon(this,R.drawable.ic_update,p.text));tile.addView(ic,new FrameLayout.LayoutParams(Ui.dp(this,20),Ui.dp(this,20),Gravity.CENTER));top.addView(tile,new LinearLayout.LayoutParams(Ui.dp(this,40),Ui.dp(this,40)));
  LinearLayout tt=new LinearLayout(this);tt.setOrientation(LinearLayout.VERTICAL);tt.setPadding(Ui.dp(this,12),0,0,0);tt.addView(Ui.text(this,mand?"Required update":"Update available",15.5f,750,p.text,0f));tt.addView(Ui.text(this,"Version "+sp.getString("upd_name","")+" · What's new",13,600,p.muted,0f),Ui.lp(-2,-2,2,this));top.addView(tt,new LinearLayout.LayoutParams(0,-2,1));
  tt.setOnClickListener(v->dialog().setTitle("What's new in "+sp.getString("upd_name","")).setMessage(sp.getString("upd_log","").isEmpty()?"Improvements and fixes.":sp.getString("upd_log","")).setPositiveButton("OK",null).show());
  if(!mand){TextView skip=Ui.text(this,"Skip",14,650,p.faint,0f);skip.setPadding(Ui.dp(this,10),Ui.dp(this,8),Ui.dp(this,4),Ui.dp(this,8));skip.setOnClickListener(v->{Updater.skip(this);bindUpdateCard();});top.addView(skip);}
  updateCard.addView(top);
  updBar=new View(this);updBar.setBackground(Ui.round(p.primary,Ui.dp(this,2),0,0));updBar.setVisibility(View.GONE);updateCard.addView(updBar,new LinearLayout.LayoutParams(0,Ui.dp(this,3)){{topMargin=Ui.dp(MainActivity.this,12);}});
  updBtn=Ui.pill(this,p,"Update",true,v->{if(PREVIEW)return;updBtn.setEnabled(false);Updater.install(this,(pct,st,done,ok)->{updBtn.setText(st);updBar.setVisibility(done?View.GONE:View.VISIBLE);LinearLayout.LayoutParams l=(LinearLayout.LayoutParams)updBar.getLayoutParams();l.width=Math.max(1,(updateCard.getWidth()-updateCard.getPaddingLeft()-updateCard.getPaddingRight())*pct/100);updBar.setLayoutParams(l);if(done)updBtn.setEnabled(true);if(done&&!ok)updBtn.setText("Update · "+st.replace("Update refused: ","failed: "));});});
  updBtn.setMinHeight(Ui.dp(this,48));updateCard.addView(updBtn,Ui.lp(-1,-2,12,this));}
 void pageTheme(LinearLayout col){lead(col,"Monochrome in both. System follows your phone's dark mode.");String cur=Ui.themePref(this);LinearLayout card=Ui.card(this,p);card.setPadding(Ui.dp(this,4),Ui.dp(this,6),Ui.dp(this,4),Ui.dp(this,6));
  String[][] opts={{"system","System default"},{"light","Light"},{"dark","Dark"}};
  for(int i=0;i<opts.length;i++){final String key=opts[i][0];LinearLayout r=Ui.row(this,p,0,opts[i][1],null,false,v->setTheme(key));if(key.equals(cur)){ImageView ck=Ui.iconView(this,R.drawable.ic_check,p.text,22);r.addView(ck);}card.addView(r);if(i<2)card.addView(Ui.divider(this,p,16));}
  col.addView(card,Ui.lp(-1,-2,32,this));}
 void setTheme(String key){if(key.equals(Ui.themePref(this)))return;Store.p(this).edit().putString("theme",key).commit();if(PREVIEW)return;Ui.applyNightMode(this);root.postDelayed(()->{if(!isFinishing()&&!isDestroyed())recreate();},150);}
 void pageDiag(LinearLayout col){SharedPreferences sp=Store.p(this);java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("h:mm:ss a",java.util.Locale.getDefault());
  lead(col,"What happened last, for when something feels off. The password is never included.");
  LinearLayout card=Ui.card(this,p);long ct=sp.getLong("last_check_time",0),ms=sp.getLong("last_signin_ms",-1),st=sp.getLong("last_signin_time",0);
  kv(card,"Status",Store.status(this),true);kv(card,"Last event",ct==0?"None yet":f.format(new java.util.Date(ct))+" · "+sp.getString("last_check",""),true);
  kv(card,"Last sign-in",ms<0?"None yet":String.format(java.util.Locale.US,"%.1f s",ms/1000.0)+" · "+sp.getString("last_signin_how","")+(st>0?" · "+f.format(new java.util.Date(st)):""),true);
  kv(card,"Triggers","Wi-Fi connect · portal detected · internet lost · screen on · sign-in notification",false);col.addView(card,Ui.lp(-1,-2,28,this));
  col.addView(Ui.section(this,p,"Timeline"),Ui.lp(-1,-2,28,this));
  LinearLayout tl=Ui.card(this,p);TextView tx=Ui.text(this,sp.getString("timeline","No sign-in recorded yet"),12,420,p.muted,0);tx.setTypeface(Typeface.MONOSPACE);tx.setTextIsSelectable(true);tl.addView(tx);col.addView(tl,Ui.lp(-1,-2,10,this));
  col.addView(Ui.pill(this,p,"Copy diagnostics",true,v->{ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("College WiFi diagnostics",diagnostics()));toast("Copied · no password inside");}),Ui.lp(-1,-2,24,this));}
 void kv(LinearLayout card,String k,String v,boolean div){TextView kt=Ui.text(this,k,12.5f,700,p.faint,0.03f);card.addView(kt);TextView vt=Ui.text(this,v,15,550,p.text,0f);card.addView(vt,Ui.lp(-1,-2,6,this));
  if(div){View d=Ui.divider(this,p,0);((LinearLayout.LayoutParams)d.getLayoutParams()).topMargin=Ui.dp(this,14);((LinearLayout.LayoutParams)d.getLayoutParams()).bottomMargin=Ui.dp(this,14);card.addView(d);}}
 void pageAbout(LinearLayout col){lead(col,"Signs you in to college Wi-Fi the moment the portal asks, then gets out of the way.");
  LinearLayout card=Ui.card(this,p);kv(card,"Version",Updater.myName(this).isEmpty()?"5.3":Updater.myName(this),true);kv(card,"Made by",Dev.NAME,true);kv(card,"How it works","No timers and no polling. Android tells the app when it sees a login portal on a saved Wi-Fi (the same signal behind “Sign in to Wi-Fi network”), and it signs in right then with your first account that works.",true);
  kv(card,"Privacy","Logins are encrypted on this phone (Android Keystore, AES-GCM) and only ever sent to the portal you confirmed for that Wi-Fi. Plain-HTTP portals send them unencrypted over the campus network; the app can't change that.",false);col.addView(card,Ui.lp(-1,-2,28,this));
  LinearLayout uc=Ui.card(this,p);uc.setPadding(Ui.dp(this,4),Ui.dp(this,6),Ui.dp(this,4),Ui.dp(this,6));LinearLayout ur=Ui.row(this,p,R.drawable.ic_update,"Check for updates",Store.p(this).getString("upd_line",null),false,null);ur.setClickable(true);
  ur.setOnClickListener(v->{if(PREVIEW)return;toast("Checking…");final android.content.Context a=getApplicationContext();new Thread(()->{String line=Updater.check(a,true);Store.p(a).edit().putString("upd_line",line).apply();runOnUiThread(()->{toast(line);if("about".equals(page))showPage("about",false);});}).start();});uc.addView(ur);col.addView(uc,Ui.lp(-1,-2,16,this));
  boolean unrestricted=PREVIEW||((PowerManager)getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName());
  LinearLayout c2=Ui.card(this,p);c2.setPadding(Ui.dp(this,4),Ui.dp(this,6),Ui.dp(this,4),Ui.dp(this,6));c2.addView(Ui.row(this,p,R.drawable.ic_battery_saver,"Battery",unrestricted?"Unrestricted":"Optimized",true,v->openBattery()));col.addView(c2,Ui.lp(-1,-2,16,this));
  col.addView(Ui.caption(this,p,"Unrestricted lets Samsung keep the app ready. Idle, it does nothing: no timers, no wakelocks.\n\nType: Nunito, SIL Open Font License. Icons: Material Symbols, Apache 2.0."),Ui.lp(-1,-2,16,this));}

 // ---------- developer card + Buy me a coffee (v4.1) ----------
 LinearLayout.LayoutParams devLp(){LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(-1,-2);l.leftMargin=Ui.dp(this,6);l.rightMargin=Ui.dp(this,2);l.topMargin=Ui.dp(this,2);return l;}
 LinearLayout devCard(){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(Ui.round(p.surface2,Ui.dp(this,26),0,0));int pad=Ui.dp(this,16);card.setPadding(pad,pad,pad,pad);
  LinearLayout who=new LinearLayout(this);who.setGravity(Gravity.CENTER_VERTICAL);
  TextView av=Ui.text(this,Dev.INITIAL,20,750,p.onPrimary,0f);av.setGravity(Gravity.CENTER);GradientDrawable ag=new GradientDrawable();ag.setShape(GradientDrawable.OVAL);ag.setColor(p.primary);av.setBackground(ag);av.setIncludeFontPadding(false);
  who.addView(av,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
  LinearLayout nm=new LinearLayout(this);nm.setOrientation(LinearLayout.VERTICAL);nm.setPadding(Ui.dp(this,14),0,0,0);
  nm.addView(Ui.text(this,"Made by",12.5f,650,p.faint,0.01f));nm.addView(Ui.text(this,Dev.NAME,17.5f,750,p.text,-0.005f),Ui.lp(-2,-2,2,this));who.addView(nm,new LinearLayout.LayoutParams(0,-2,1));
  card.addView(who);
  String[][] links={{"linkedin","LinkedIn",Dev.LINKEDIN},{"github","GitHub",Dev.GITHUB},{"instagram","Instagram",Dev.INSTAGRAM},{"email","Email",Dev.EMAIL}};
  boolean any=false;for(String[] l:links){final String u=Dev.url(l[0],l[2]);if(u==null)continue;
   LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(Ui.dp(this,10),Ui.dp(this,10),Ui.dp(this,8),Ui.dp(this,10));r.setBackground(Ui.ripple(p,null,Ui.dp(this,16)));r.setClickable(true);
   if("linkedin".equals(l[0])){TextView in=Ui.text(this,"in",11.5f,800,p.surface2,-0.02f);in.setGravity(Gravity.CENTER);in.setIncludeFontPadding(false);in.setBackground(Ui.round(p.text,Ui.dp(this,5),0,0));LinearLayout.LayoutParams il=new LinearLayout.LayoutParams(Ui.dp(this,20),Ui.dp(this,20));il.rightMargin=Ui.dp(this,12);r.addView(in,il);}
   else{ImageView ic=Ui.iconView(this,R.drawable.ic_link,p.muted,18);((LinearLayout.LayoutParams)ic.getLayoutParams()).rightMargin=Ui.dp(this,12);r.addView(ic);}
   r.addView(Ui.text(this,l[1],15f,650,p.text,0f),new LinearLayout.LayoutParams(0,-2,1));TextView hv=Ui.text(this,Dev.label(l[0],l[2]),13.5f,600,p.faint,0f);hv.setSingleLine(true);hv.setEllipsize(android.text.TextUtils.TruncateAt.END);hv.setMaxWidth(Ui.dp(this,130));r.addView(hv);
   r.setContentDescription(l[1]+" "+Dev.label(l[0],l[2]));r.setOnClickListener(v->{if(PREVIEW)return;try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception e){toast("No app can open this link");}});
   card.addView(r,Ui.lp(-1,-2,any?0:10,this));any=true;}
  // Buy me a coffee: soft pill, then quick amounts
  LinearLayout cof=new LinearLayout(this);cof.setGravity(Gravity.CENTER);cof.setMinimumHeight(Ui.dp(this,52));cof.setBackground(Ui.ripple(p,Ui.soft(this,p,p.primary,26,0.6f),Ui.dp(this,26)));cof.setClickable(true);cof.setFocusable(true);cof.setContentDescription("Buy me a coffee");
  ImageView ci=Ui.iconView(this,R.drawable.ic_coffee,p.onPrimary,20);((LinearLayout.LayoutParams)ci.getLayoutParams()).rightMargin=Ui.dp(this,10);cof.addView(ci);cof.addView(Ui.text(this,"Buy me a coffee",16f,700,p.onPrimary,0f));
  cof.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);coffee(0);});card.addView(cof,Ui.lp(-1,-2,16,this));
  LinearLayout chips=new LinearLayout(this);for(int i=0;i<Dev.AMOUNTS.length;i++){final int a=Dev.AMOUNTS[i];TextView c=Ui.text(this,"\u20B9"+a,14.5f,700,p.text,0f);c.setGravity(Gravity.CENTER);c.setMinHeight(Ui.dp(this,40));
   c.setBackground(Ui.ripple(p,Ui.round(p.surface,Ui.dp(this,20),0,0),Ui.dp(this,20)));c.setClickable(true);c.setContentDescription("Buy a coffee for "+a+" rupees");c.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);coffee(a);});
   LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(0,-2,1);if(i>0)cl.leftMargin=Ui.dp(this,8);chips.addView(c,cl);}
  card.addView(chips,Ui.lp(-1,-2,10,this));
  LinearLayout idr=new LinearLayout(this);idr.setGravity(Gravity.CENTER);idr.setPadding(0,Ui.dp(this,10),0,Ui.dp(this,2));idr.setBackground(Ui.ripple(p,null,Ui.dp(this,14)));idr.setClickable(true);
  TextView idt=Ui.text(this,"UPI · "+Dev.UPI_ID,12.5f,600,p.faint,0f);idt.setSingleLine(true);idt.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);idr.addView(idt,new LinearLayout.LayoutParams(0,-2,1));
  ImageView cp=Ui.iconView(this,R.drawable.ic_content_copy,p.faint,15);((LinearLayout.LayoutParams)cp.getLayoutParams()).leftMargin=Ui.dp(this,6);idr.addView(cp);idr.setOnClickListener(v->copyUpi());idr.setContentDescription("Copy UPI ID");
  card.addView(idr,Ui.lp(-1,-2,4,this));
  return card;}
 /** Opens the UPI chooser (GPay/PhonePe/Paytm…); if no app can take upi://, a soft sheet with the ID and Copy. */
 void coffee(int amount){Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(Dev.upi(amount)));
  if(!PREVIEW){boolean has=false;try{has=!getPackageManager().queryIntentActivities(i,0).isEmpty();}catch(Exception ignored){}
   if(has){try{startActivity(Intent.createChooser(i,amount>0?"Pay \u20B9"+amount+" with":"Pay with"));return;}catch(Exception ignored){}}}
  openSheet(true);}
 void copyUpi(){try{ClipboardManager cb=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cb.setPrimaryClip(ClipData.newPlainText("UPI ID",Dev.UPI_ID));toast("UPI ID copied");}catch(Exception e){toast(Dev.UPI_ID);}}
 void openSheet(boolean anim){if(sheetOpen)return;sheetOpen=true;sheetHost.removeAllViews();
  LinearLayout s=new LinearLayout(this);sheet=s;s.setOrientation(LinearLayout.VERTICAL);s.setGravity(Gravity.CENTER_HORIZONTAL);s.setClickable(true);
  s.setBackground(new Ui.Soft(p.surface,Ui.dp(this,32),p.dark?0x99000000:0x2a1c1c24,Ui.dp(this,40),0,0,0));s.setPadding(Ui.dp(this,24),Ui.dp(this,12),Ui.dp(this,24),insetBottom+Ui.dp(this,40));
  View handle=new View(this);handle.setBackground(Ui.round(p.hairline,Ui.dp(this,3),0,0));s.addView(handle,new LinearLayout.LayoutParams(Ui.dp(this,40),Ui.dp(this,5)));
  FrameLayout tile=new FrameLayout(this);tile.setBackground(Ui.round(p.surface2,Ui.dp(this,24),0,0));ImageView ti=new ImageView(this);ti.setImageDrawable(Ui.icon(this,R.drawable.ic_coffee,p.text));tile.addView(ti,new FrameLayout.LayoutParams(Ui.dp(this,28),Ui.dp(this,28),Gravity.CENTER));
  s.addView(tile,new LinearLayout.LayoutParams(Ui.dp(this,64),Ui.dp(this,64)){{topMargin=Ui.dp(MainActivity.this,24);}});
  TextView h=Ui.headline(this,p,"Buy me a coffee");h.setGravity(Gravity.CENTER);s.addView(h,Ui.lp(-2,-2,18,this));
  TextView b=Ui.body(this,p,"No UPI app found on this phone. Send any amount to this UPI ID from GPay, PhonePe or Paytm on any phone.");b.setGravity(Gravity.CENTER);s.addView(b,Ui.lp(-1,-2,8,this));
  LinearLayout box=new LinearLayout(this);box.setGravity(Gravity.CENTER_VERTICAL);box.setBackground(Ui.round(p.surface2,Ui.dp(this,22),0,0));box.setPadding(Ui.dp(this,18),Ui.dp(this,8),Ui.dp(this,8),Ui.dp(this,8));
  LinearLayout idc=new LinearLayout(this);idc.setOrientation(LinearLayout.VERTICAL);idc.addView(Ui.text(this,"UPI ID",12f,650,p.faint,0.02f));TextView idv=Ui.text(this,Dev.UPI_ID,15.5f,700,p.text,0f);idv.setSingleLine(true);idv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);idv.setTextIsSelectable(!PREVIEW);idc.addView(idv,Ui.lp(-2,-2,2,this));box.addView(idc,new LinearLayout.LayoutParams(0,-2,1));
  LinearLayout copy=new LinearLayout(this);copy.setGravity(Gravity.CENTER);copy.setPadding(Ui.dp(this,16),0,Ui.dp(this,18),0);copy.setMinimumHeight(Ui.dp(this,44));copy.setBackground(Ui.ripple(p,Ui.round(p.primary,Ui.dp(this,22),0,0),Ui.dp(this,22)));copy.setClickable(true);
  ImageView cpi=Ui.iconView(this,R.drawable.ic_content_copy,p.onPrimary,16);((LinearLayout.LayoutParams)cpi.getLayoutParams()).rightMargin=Ui.dp(this,8);copy.addView(cpi);copy.addView(Ui.text(this,"Copy",14.5f,700,p.onPrimary,0f));copy.setOnClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);copyUpi();});
  box.addView(copy);s.addView(box,Ui.lp(-1,-2,24,this));
  TextView done=Ui.textButton(this,p,"Done",v->closeSheet(true));s.addView(done,Ui.lp(-1,-2,8,this));
  sheetHost.addView(s,new FrameLayout.LayoutParams(-1,-2));sheetHost.setVisibility(View.VISIBLE);sheetScrim.setVisibility(View.VISIBLE);
  if(!anim){sheetScrim.setAlpha(1f);s.setTranslationY(0);return;}
  sheetScrim.animate().alpha(1f).setDuration(220).start();s.setTranslationY(Ui.dp(this,420));s.animate().translationY(0).setDuration(340).setInterpolator(EASE).start();}
 void closeSheet(boolean anim){if(!sheetOpen)return;sheetOpen=false;final LinearLayout s=sheet;
  if(!anim||s==null){sheetHost.setVisibility(View.GONE);sheetHost.removeAllViews();sheetScrim.setVisibility(View.GONE);sheetScrim.setAlpha(0f);return;}
  sheetScrim.animate().alpha(0f).setDuration(200).withEndAction(()->sheetScrim.setVisibility(View.GONE)).start();
  s.animate().translationY(s.getHeight()+Ui.dp(this,40)).setDuration(240).setInterpolator(EASE).withEndAction(()->{if(!sheetOpen){sheetHost.setVisibility(View.GONE);sheetHost.removeAllViews();}}).start();}
 void openBattery(){try{startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName())));}catch(Exception e){try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception ignored){}}}
 AlertDialog.Builder dialog(){return new AlertDialog.Builder(this,p.dark?android.R.style.Theme_DeviceDefault_Dialog_Alert:android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);}

 // ---------- setup & permissions (logic kept from v3) ----------
 void checkPermissions(){if(checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"},1);return;}if(!Net.locationOn(this)){dialog().setMessage("Turn on Location so Android can tell which Wi-Fi you're on.").setPositiveButton("Open Location settings",(x,y)->startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))).setNegativeButton("Later",null).show();return;}if(Build.VERSION.SDK_INT>=30&&checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION")!=PackageManager.PERMISSION_GRANTED){dialog().setTitle("Allow background location").setMessage("In Android settings, set Location to ‘Allow all the time’. This is how the app recognizes your Wi-Fi after you close it.").setPositiveButton("Open app settings",(x,y)->{Intent i=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));startActivity(i);}).setNegativeButton("Later",null).show();return;}if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},2);return;}completeSetup();}
 void completeSetup(){if(pendingSetup){pendingSetup=false;Store.enable(this,true);Brain.get(this).credentialsChanged();Store.p(this).edit().remove("suppressed_net").apply();Triggers.register(this);ensureService();if(!Session.kind(Store.status(this)).equals("CONNECTED"))Store.status(this,"Watching "+Store.ssid(this));refresh(true);toast("Saved · auto sign-in is on");}else refresh(true);
  PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);if(Store.enabled(this)&&!Store.p(this).getBoolean("battery_asked",false)&&!pm.isIgnoringBatteryOptimizations(getPackageName())){Store.p(this).edit().putBoolean("battery_asked",true).apply();
   dialog().setTitle("Keep it ready").setMessage("Set battery use to Unrestricted so Samsung doesn't put the app to sleep. Idle, it uses no timers or wakelocks.").setPositiveButton("Open battery settings",(x,y)->openBattery()).setNegativeButton("Later",null).show();}}
 void ensureService(){if(Store.enabled(this)&&!Brain.serviceAlive){try{startForegroundService(new Intent(this,MonitorService.class));}catch(Exception ignored){}}}
 String diagnostics(){android.content.SharedPreferences sp=Store.p(this);java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.US);long ct=sp.getLong("last_check_time",0);
  StringBuilder nets=new StringBuilder();for(Store.NetProfile np:Store.nets(this)){nets.append("\n• ").append(np.ssid()).append(" · ").append(np.portalName()).append(" · ").append(np.n.loginUrl).append(" · sends only to ").append(np.n.hosts).append(np.n.r("web").equals("1")?" · hidden-browser recipe":"");
   for(int i=0;i<np.accounts.size();i++){Accounts.Account a=np.accounts.get(i);nets.append("\n   ").append(i+1).append(". ").append(mask(a.user)).append(a.state.isEmpty()?"":" · "+a.state+(a.message.isEmpty()?"":" ("+a.message+")"));}}
  String r="College WiFi "+Updater.myName(this)+" diagnostics (no passwords)\nMode: event-driven (no polling) · triggers: Wi-Fi connect, captive portal detected, validation lost, screen on (max every 2 min), sign-in notification\nAndroid "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT+" · "+Build.MANUFACTURER+" "+Build.MODEL+"\nAuto sign-in: "+(Store.enabled(this)?"on":"off")+(sp.getString("suppressed_net",null)!=null?" (paused by Sign out)":"")+" · listener service "+(Brain.serviceAlive?"running":"not running")
   +"\nSaved networks:"+(nets.length()==0?" none":nets.toString())+"\nMatch: "+sp.getString("match_note","none yet")+"\nStatus: "+Store.status(this)+"\nLast check: "+(ct==0?"none":f.format(new java.util.Date(ct))+" "+sp.getString("last_check",""))+"\nLast sign-in: "+sp.getLong("last_signin_ms",-1)+" ms after trigger ("+sp.getString("last_signin_how","")+")"
   +"\n\nTimeline (ms from Wi-Fi available / trigger):\n"+sp.getString("timeline","none yet")+"\n\nLast sign-out:\n"+sp.getString("logout_timeline","none yet");
  for(Store.NetProfile np:Store.nets(this))for(Accounts.Account a:np.accounts){String[] c=Store.creds(np,a);if(c!=null){if(c[1]!=null&&c[1].length()>=3)r=r.replace(c[1],"[redacted]");c[0]=null;c[1]=null;}}return r;}
 static String mask(String u){if(u==null||u.length()<=4)return "****";return u.substring(0,2)+"***"+u.substring(u.length()-2);}
 @Override protected void onResume(){super.onResume();Store.p(this).registerOnSharedPreferenceChangeListener(prefL);
  if(!PREVIEW){Brain.get(this).reconcile();if(Store.enabled(this)&&Store.has(this)){ensureService();Triggers.register(this);Brain.get(this).screenOn();}
   if(pendingSetup&&checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")==PackageManager.PERMISSION_GRANTED&&Net.locationOn(this)&&checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION")==PackageManager.PERMISSION_GRANTED)checkPermissions();}
  if(!PREVIEW&&System.currentTimeMillis()-Store.p(this).getLong("upd_checked",0)>3600_000L)Updater.checkSoon(this);
  if(!PREVIEW)UsageTracker.portalIfDue(this,UsageTracker.PORTAL_ON_OPEN_MS);bindUsage("usage".equals(page));
  refresh(false);}
 @Override protected void onPause(){Store.p(this).unregisterOnSharedPreferenceChangeListener(prefL);super.onPause();}
 @Override public void onBackPressed(){if(sheetOpen){closeSheet(true);return;}if(drawerOpen){closeDrawer(true);return;}if(page!=null){closePage(true);return;}super.onBackPressed();}
 @Override public void onRequestPermissionsResult(int request,String[] names,int[] values){super.onRequestPermissionsResult(request,names,values);if(request==2){completeSetup();return;}if(request==3){if("setup".equals(page))showPage("setup",false);return;}if(values.length>0&&values[0]==PackageManager.PERMISSION_GRANTED)checkPermissions();else refresh(true);}
 void hideKeyboard(){View f=getCurrentFocus();if(f!=null)((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(f.getWindowToken(),0);}
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
 // ---------- v5.2 daily data usage ----------
 void buildUsageCard(){usageCard=new LinearLayout(this);usageCard.setOrientation(LinearLayout.VERTICAL);float rad=Ui.dp(this,24);usageCard.setBackground(Ui.ripple(p,Ui.soft(this,p,p.surface,24,0.8f),rad));usageCard.setPadding(Ui.dp(this,14),Ui.dp(this,12),Ui.dp(this,16),Ui.dp(this,12));
  usageCard.setClickable(true);usageCard.setOnClickListener(v->showPage("usage",true));usageCard.setVisibility(View.GONE);
  LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
  usageRing=new UsageViews.Ring(this,p,5);row.addView(usageRing,new LinearLayout.LayoutParams(Ui.dp(this,58),Ui.dp(this,58)));
  LinearLayout texts=new LinearLayout(this);texts.setOrientation(LinearLayout.VERTICAL);texts.setPadding(Ui.dp(this,14),0,Ui.dp(this,8),0);
  usageLine=Ui.text(this,"",15,700,p.text,-0.005f);usageLine.setMaxLines(2);usageLine.setEllipsize(android.text.TextUtils.TruncateAt.END);texts.addView(usageLine);
  usageSub=Ui.caption(this,p,"");usageSub.setMaxLines(2);usageSub.setEllipsize(android.text.TextUtils.TruncateAt.END);texts.addView(usageSub,Ui.lp(-1,-2,3,this));
  row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));
  usageBars=new UsageViews.Bars(this,p);usageBars.setContentDescription("Last 7 days");row.addView(usageBars,new LinearLayout.LayoutParams(Ui.dp(this,52),Ui.dp(this,32)));
  usageCard.addView(row);
  usageFix=Ui.text(this,"Make it exact: allow usage\u00a0access\u00a0\u203a",13,680,p.muted,0f);usageFix.setPadding(Ui.dp(this,72),Ui.dp(this,8),0,Ui.dp(this,2));usageFix.setOnClickListener(v->{if(!PREVIEW)UsageTracker.openUsageAccess(this);});usageFix.setVisibility(View.GONE);usageCard.addView(usageFix);
  mainCol.addView(usageCard,new LinearLayout.LayoutParams(-1,-2){{bottomMargin=Ui.dp(MainActivity.this,14);}});}
 /** Recompute off the main thread (Android's counter is a binder call), then draw the card (and the page, if open). */
 void bindUsage(boolean page2){if(usageCard==null)return;boolean show=Store.has(this);usageCard.setVisibility(show?View.VISIBLE:View.GONE);if(!show)return;
  if(PREVIEW){renderUsage(UsageTracker.compute(this),page2);return;}
  UsageTracker.computeAsync(this,v->runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())renderUsage(v,page2);}));}
 void renderUsage(UsageTracker.View v,boolean page2){boolean first=lastUsage==null;lastUsage=v;boolean cap=v.cap>0;String[] amt=Usage.bytes(v.used,v.cap).split(" ");
  usageRing.set(v.fraction(),cap,cap?Math.round(v.fraction()*100)+"%":amt[0],cap?"used":amt.length>1?amt[1]:"");
  usageLine.setText(Usage.line(v.used,v.cap));
  usageSub.setText(v.fromPortal?(cap?"of "+Usage.bytes(v.cap)+" \u00b7 ":"")+"campus portal \u00b7 "+timeOf(v.portalAt):"\u2193 "+Usage.bytes(v.phoneDown)+"  \u2191 "+Usage.bytes(v.phoneUp)+" \u00b7 this phone"+(v.precise?"":" \u00b7 approx"));
  long[] tot=new long[v.down.length];for(int i=0;i<tot.length;i++)tot[i]=v.down[i]+v.up[i];usageBars.set(tot,null,0);
  usageFix.setVisibility(!v.precise&&!v.fromPortal?View.VISIBLE:View.GONE);
  usageCard.setContentDescription(usageLine.getText()+". "+usageSub.getText()+". Opens data usage.");
  if("usage".equals(page)&&(page2||first))showPage("usage",false);}
 String shortUsage(){UsageTracker.View v=lastUsage;if(v==null)return null;return v.cap>0?Math.round(v.fraction()*100)+"%":Usage.bytes(v.used);}
 String timeOf(long t){return t<=0?"never":android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(t));}
 static String dayLetter(String key){try{java.util.Calendar c=java.util.Calendar.getInstance();c.clear();c.set(Integer.parseInt(key.substring(0,4)),Integer.parseInt(key.substring(4,6))-1,Integer.parseInt(key.substring(6,8)));
   return new java.text.SimpleDateFormat("EEE",java.util.Locale.getDefault()).format(c.getTime()).substring(0,1);}catch(Exception e){return "";}}
 void pageUsage(LinearLayout col){UsageTracker.View v=lastUsage;if(v==null){lead(col,"Counting\u2026");bindUsage(true);return;}SharedPreferences sp=Store.p(this);
  lead(col,"Counted only while you're on campus Wi-Fi, and it never leaves this phone. Resets at midnight.");
  // today
  LinearLayout today=Ui.card(this,p);today.setGravity(Gravity.CENTER_HORIZONTAL);boolean cap=v.cap>0;String[] amt=Usage.bytes(v.used,v.cap).split(" ");
  UsageViews.Ring big=new UsageViews.Ring(this,p,9);big.set(v.fraction(),cap,cap?Math.round(v.fraction()*100)+"%":amt[0],cap?"used today":amt.length>1?amt[1]+" today":"");today.addView(big,new LinearLayout.LayoutParams(Ui.dp(this,148),Ui.dp(this,148)));
  TextView l=Ui.headline(this,p,v.line());l.setGravity(Gravity.CENTER);today.addView(l,Ui.lp(-2,-2,16,this));
  String src=v.fromPortal?"From the campus portal \u00b7 all devices on this login \u00b7 updated "+timeOf(v.portalAt)+(v.portalStale?" (old)":""):cap?"This phone vs. your daily cap":"Set a daily cap below to see what's left";
  TextView s=Ui.caption(this,p,src);s.setGravity(Gravity.CENTER);today.addView(s,Ui.lp(-2,-2,6,this));
  col.addView(today,Ui.lp(-1,-2,8,this));
  if(!v.precise){LinearLayout ex=Ui.card(this,p);ex.addView(Ui.headline(this,p,"Count this phone exactly"));ex.addView(Ui.body(this,p,"Right now it's an estimate from the whole-phone counter. Allow \"Usage access\" and Android shares its own Wi-Fi counter. Nothing leaves the phone."),Ui.lp(-1,-2,6,this));
   ex.addView(Ui.pill(this,p,"Allow usage access",true,x->{if(!PREVIEW)UsageTracker.openUsageAccess(this);}),Ui.lp(-1,-2,14,this));col.addView(ex,Ui.lp(-1,-2,14,this));}
  // breakdown
  LinearLayout d=Ui.card(this,p);
  kv(d,"This phone today","\u2193 "+Usage.bytes(v.phoneDown)+"   \u2191 "+Usage.bytes(v.phoneUp)+"   = "+Usage.bytes(v.phone())+(v.precise?"":"  (approx)"),true);
  if(v.portal!=null)kv(d,"Campus account today (all devices)",(v.portal.down>0||v.portal.up>0?"\u2193 "+Usage.bytes(Math.max(0,v.portal.down))+"   \u2191 "+Usage.bytes(Math.max(0,v.portal.up))+"   = ":"")+""+Usage.bytes(v.portal.used())+(v.portal.cap()>0?" of "+Usage.bytes(v.portal.cap()):""),true);
  kv(d,"Left today",v.left>=0?Usage.bytes(v.left,v.cap)+(v.fromPortal?" (campus quota)":" (your cap)"):"No cap set",false);
  col.addView(d,Ui.lp(-1,-2,14,this));
  // history
  col.addView(Ui.section(this,p,"Last 7 days \u00b7 this phone"),Ui.lp(-1,-2,22,this));
  LinearLayout h=Ui.card(this,p);long[] tot=new long[v.down.length];long sum=0;String[] labels=new String[v.days.length];for(int i=0;i<tot.length;i++){tot[i]=v.down[i]+v.up[i];sum+=tot[i];labels[i]=dayLetter(v.days[i]);}
  UsageViews.Bars bars=new UsageViews.Bars(this,p);bars.set(tot,labels,v.fromPortal?0:v.cap);bars.setContentDescription("Last 7 days");h.addView(bars,new LinearLayout.LayoutParams(-1,Ui.dp(this,104)));
  h.addView(Ui.caption(this,p,"Today "+Usage.bytes(tot[tot.length-1])+" \u00b7 7 days "+Usage.bytes(sum)+" \u00b7 about "+Usage.bytes(sum/tot.length)+" a day"),Ui.lp(-1,-2,12,this));
  col.addView(h,Ui.lp(-1,-2,10,this));
  // settings
  col.addView(Ui.section(this,p,"Daily cap"),Ui.lp(-1,-2,22,this));
  LinearLayout c=Ui.card(this,p);long myCap=UsageTracker.cap(this);
  c.addView(Ui.body(this,p,v.portal!=null&&v.portal.cap()>0?"The campus portal says "+Usage.bytes(v.portal.cap())+" a day, so that's what's used. Your cap is the backup when the portal can't be read.":"Pick what you want to stay under. Remaining = cap \u2212 what this phone used today."));
  LinearLayout chips=new LinearLayout(this);chips.setGravity(Gravity.CENTER_VERTICAL);long[] opts={0,Usage.GB,2*Usage.GB,5*Usage.GB,10*Usage.GB};boolean preset=false;
  for(long o:opts){boolean on=o==myCap&&!capCustom;if(on)preset=true;chips.addView(chip(o==0?"Off":Usage.bytes(o),on,x->{capCustom=false;UsageTracker.cap(this,o);bindUsage(true);}),new LinearLayout.LayoutParams(0,Ui.dp(this,40),1){{rightMargin=Ui.dp(MainActivity.this,6);}});}
  boolean custom=capCustom||(!preset&&myCap>0);chips.addView(chip("Other",custom,x->{capCustom=true;showPage("usage",false);}),new LinearLayout.LayoutParams(0,Ui.dp(this,40),1.25f));
  c.addView(chips,Ui.lp(-1,-2,14,this));
  if(custom){LinearLayout fr=new LinearLayout(this);fr.setGravity(Gravity.CENTER_VERTICAL);final EditText f=Ui.field(this,p,"e.g. 3 GB or 750 MB",myCap>0&&!preset?Usage.bytes(myCap):"",android.text.InputType.TYPE_CLASS_TEXT);fr.addView(f,new LinearLayout.LayoutParams(0,-2,1));
   TextView set=Ui.pill(this,p,"Set",true,x->{long val=Usage.parseCap(f.getText().toString());if(val<0){toast("Try something like 3 GB or 750 MB");return;}hideKeyboard();capCustom=false;UsageTracker.cap(this,val);bindUsage(true);});fr.addView(set,new LinearLayout.LayoutParams(Ui.dp(this,88),-2){{leftMargin=Ui.dp(MainActivity.this,10);}});
   c.addView(fr,Ui.lp(-1,-2,14,this));}
  col.addView(c,Ui.lp(-1,-2,10,this));
  // switches
  LinearLayout o=Ui.card(this,p);o.setPadding(Ui.dp(this,4),Ui.dp(this,6),Ui.dp(this,4),Ui.dp(this,6));
  o.addView(toggleRow(R.drawable.ic_info,"Alert at 80% and 100%",UsageTracker.alerts(this),on->sp.edit().putBoolean("u_alerts",on).apply()));o.addView(Ui.divider(this,p,70));
  o.addView(toggleRow(R.drawable.ic_monitor_heart,"Show in status notification",UsageTracker.inNote(this),on->{sp.edit().putBoolean("u_in_note",on).apply();MonitorService.poke();}));o.addView(Ui.divider(this,p,70));
  o.addView(toggleRow(R.drawable.ic_router,"Read quota from campus portal",UsageTracker.portalOn(this),on->{sp.edit().putBoolean("u_portal_on",on).remove("u_portal_try").apply();if(on&&!PREVIEW)UsageTracker.portalIfDue(this,0);bindUsage(true);}));o.addView(Ui.divider(this,p,70));
  o.addView(Ui.row(this,p,R.drawable.ic_lock,"Usage access",v.precise?"On":"Off",true,x->{if(!PREVIEW)UsageTracker.openUsageAccess(this);}));
  col.addView(o,Ui.lp(-1,-2,22,this));
  String pe=sp.getString("u_portal_err",null);long pa=sp.getLong("u_portal_at",0);
  String note=!UsageTracker.portalOn(this)?"Campus portal reading is off.":pa>0&&pe==null?"Campus portal last read "+timeOf(pa)+". It's read every 15 min while you're on campus Wi-Fi with the screen on.":pe!=null?"Campus portal: "+pe+".":"Campus portal not read yet. It's read with your saved login once you're signed in on campus Wi-Fi.";
  col.addView(Ui.caption(this,p,note+" Reading the portal is new and hasn't been tried on the campus network yet; if it can't, the phone's own count and your cap are used."),Ui.lp(-1,-2,12,this));
  if(UsageTracker.portalOn(this))col.addView(Ui.textButton(this,p,"Read campus portal now",x->{if(PREVIEW)return;Store.p(this).edit().remove("u_portal_try").apply();Brain.get(this).portalAsked=0;UsageTracker.portalIfDue(this,0);toast("Reading the campus portal\u2026");}),Ui.lp(-1,-2,6,this));}
 TextView chip(String label,boolean on,View.OnClickListener l){TextView t=Ui.text(this,label,14,700,on?p.onPrimary:p.text,0f);t.setGravity(Gravity.CENTER);float r=Ui.dp(this,20);t.setBackground(Ui.ripple(p,Ui.round(on?p.primary:p.surface2,r,0,0),r));t.setClickable(true);t.setOnClickListener(l);t.setMaxLines(1);return t;}
 LinearLayout toggleRow(int icon,String title,boolean on,Toggle.Listener l){LinearLayout r=Ui.row(this,p,icon,title,null,false,null);Toggle tg=new Toggle(this,p,on,l);r.addView(tg);r.setOnClickListener(v->tg.performClick());return r;}

}
