package tv.gridiron.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.*;

/** Shared visual primitives for the TV home, player chrome, and native dialogs. */
final class Ui {
    static final int BACKGROUND=0xff101112, SURFACE=0xff1b1d1f, RAISED=0xff292c2f;
    static final int TEXT=0xfff3f2ee, MUTED=0xffa4a7aa, LINE=0xff36393c, LIVE=0xffed8c80;
    static final int OVERLAY=0xf0101112;
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static TextView text(Context c,String value,int size,int color){TextView v=new TextView(c);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));return v;}
    static GradientDrawable surface(Context c,int fill,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,6));d.setStroke(dp(c,2),stroke);return d;}
    static StateListDrawable states(Context c){
        StateListDrawable d=new StateListDrawable();d.addState(new int[]{android.R.attr.state_focused},surface(c,TEXT,TEXT));d.addState(new int[]{android.R.attr.state_pressed},surface(c,TEXT,TEXT));d.addState(new int[]{},surface(c,SURFACE,LINE));return d;
    }
    static void styleButton(Button b){
        b.setAllCaps(false);b.setTextSize(14);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{android.R.attr.state_focused},{android.R.attr.state_pressed},{}},new int[]{MUTED,BACKGROUND,BACKGROUND,TEXT}));
        b.setBackground(states(b.getContext()));b.setStateListAnimator(null);b.setElevation(0);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(b.getContext(),44));b.setMinimumHeight(dp(b.getContext(),44));b.setPadding(dp(b.getContext(),16),0,dp(b.getContext(),16),0);
    }
    static Button button(Context c,String label,Runnable action){Button b=new Button(c);b.setText(label);styleButton(b);b.setOnClickListener(v->action.run());return b;}
    static ImageView brandMark(Context c){
        ImageView mark=new ImageView(c);mark.setImageResource(R.drawable.ape_logo);mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mark.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);return mark;
    }
    static void icon(TextView view,int resource){
        if(java.util.Objects.equals(view.getTag(R.id.ui_icon),resource))return;
        view.setTag(R.id.ui_icon,resource);var drawable=view.getContext().getDrawable(resource).mutate();
        drawable.setBounds(0,0,dp(view.getContext(),20),dp(view.getContext(),20));drawable.setTintList(view.getTextColors());
        view.setCompoundDrawablePadding(dp(view.getContext(),8));view.setCompoundDrawablesRelative(drawable,null,null,null);
    }
    static CharSequence iconLabel(Context c,String label,int resource){
        var text=new android.text.SpannableString("  "+label);var drawable=c.getDrawable(resource).mutate();drawable.setBounds(0,0,dp(c,20),dp(c,20));
        text.setSpan(new android.text.style.ImageSpan(drawable,android.text.style.ImageSpan.ALIGN_BASELINE),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);return text;
    }
    static EditText input(Context c,String hint){
        EditText e=new EditText(c);e.setHint(hint);e.setSingleLine(true);e.setTextSize(16);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12));
        StateListDrawable bg=new StateListDrawable();bg.addState(new int[]{android.R.attr.state_focused},surface(c,BACKGROUND,TEXT));bg.addState(new int[]{},surface(c,BACKGROUND,LINE));e.setBackground(bg);return e;
    }
    static void styleDialog(AlertDialog d){
        Context c=d.getContext();d.getWindow().setBackgroundDrawable(surface(c,SURFACE,LINE));d.getWindow().setDimAmount(.72f);
        d.getWindow().setLayout(Math.min(dp(c,640),Math.round(c.getResources().getDisplayMetrics().widthPixels*.88f)),-2);
        for(int which:new int[]{-1,-2,-3}){Button b=d.getButton(which);if(b!=null){styleButton(b);if(b.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams){var p=(android.view.ViewGroup.MarginLayoutParams)b.getLayoutParams();p.height=dp(c,44);p.setMargins(dp(c,4),0,dp(c,4),0);b.setLayoutParams(p);}if(b.getParent() instanceof android.view.View){android.view.View bar=(android.view.View)b.getParent();bar.setPadding(bar.getPaddingLeft(),dp(c,8),bar.getPaddingRight(),dp(c,16));}}}
        ListView list=d.getListView();if(list!=null){StateListDrawable selector=new StateListDrawable();selector.addState(new int[]{},surface(c,RAISED,TEXT));list.setSelector(selector);list.setDivider(new android.graphics.drawable.ColorDrawable(LINE));list.setDividerHeight(dp(c,1));list.setPadding(dp(c,16),0,dp(c,16),dp(c,8));}
    }
    static final class DialogBuilder extends AlertDialog.Builder {
        DialogBuilder(Context c){super(c,R.style.ApeDialog);}
        @Override public AlertDialog.Builder setTitle(CharSequence title){TextView v=text(getContext(),title.toString(),22,TEXT);v.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));v.setPadding(dp(getContext(),24),dp(getContext(),24),dp(getContext(),24),dp(getContext(),20));return super.setCustomTitle(v);}
        @Override public AlertDialog show(){AlertDialog d=super.show();styleDialog(d);return d;}
    }
}
