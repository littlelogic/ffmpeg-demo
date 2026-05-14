/**
 * Copyright (C) 2015 ogaclejapan
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ogaclejapan.smarttablayout;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;


import java.util.ArrayList;
import java.util.List;

/**
 * To be used with ViewPager to provide a tab indicator component which give constant feedback as
 * to
 * the user's scroll progress.
 * <p>
 * To use the component, simply add it to your view hierarchy. Then in your
 * {@link android.app.Activity} or {@link android.app.Fragment}, {@link
 * androidx.fragment.app.Fragment} call
 * {@link #setViewPager(ViewPager)} providing it the ViewPager this layout
 * is being used for.
 * <p>
 * The colors can be customized in two ways. The first and simplest is to provide an array of
 * colors
 * via {@link #setSelectedIndicatorColors(int...)} and {@link #setDividerColors(int...)}. The
 * alternative is via the {@link TabColorizer} interface which provides you complete control over
 * which color is used for any individual position.
 * <p>
 * The views used as tabs can be customized by calling {@link #setCustomTabView(int, int)},
 * providing the layout ID of your custom layout.
 * <p>
 * Forked from Google Samples &gt; SlidingTabsBasic &gt;
 * <a href="https://developer.android.com/samples/SlidingTabsBasic/src/com.example.android.common/view/SlidingTabLayout.html">SlidingTabLayout</a>
 */

/**
 *
 *





 stl_indicatorAlwaysInCenter	如果设置为true，活动标签总是显示在中心（如报摊谷歌应用程序），默认FALSE	 	 
 stl_indicatorWithoutPadding	如果设置为true，在没有制表符的情况下绘制指示符，默认为false	 	 	 	 
 stl_indicatorInFront	在下划线前画指示器，默认为false	 	 	 	 	 	 
 stl_indicatorInterpolation	 	 	 	 	 	 	 	 	 	 
 stl_indicatorGravity	指示器的绘制位置：“底部”或“顶部”或“中心”，默认“底部”	 	 	 	 
 stl_indicatorColor	指示剂颜色	 	 	 	 	 	 	 	 
 stl_indicatorColors	多种颜色的指示器，可以为每个标签设置颜色	 	 	 	 	 
 stl_indicatorThickness	指示器厚度	 	 	 	 	 	 	 	 
 stl_indicatorWidth	指示器的宽度，默认“自动”	 	 	 	 	 	 	 
 stl_indicatorCornerRadius	圆角圆角半径指示器	 	 	 	 	 	 	 	 
 stl_overlineColor	顶线颜色	 	 	 	 	 	 	 	 	 
 stl_overlineThickness	顶线厚度	 	 	 	 	 	 	 	 	 
 stl_underlineColor	底线颜色	 	 	 	 	 	 	 	 	 
 stl_underlineThickness	底线厚度	 	 	 	 	 	 	 	 	 
 stl_dividerColor	标签之间的分隔符颜色	 	 	 	 	 	 	 
 stl_dividerColors	多种颜色的制表符之间的分隔符，可以设置每个标签的颜色	 	 	 	 
 stl_dividerThickness	分压器厚度	 	 	 	 	 	 	 	 
 stl_defaultTabBackground	背景中每个选项卡。一般来说，设置statelistdrawable	 	 	 	 	 
 stl_defaultTabTextAllCaps	如果设置为TRUE，所有标签标题将大写，默认为真	 	 	 	 	 
 stl_defaultTabTextColor	默认包含的选项卡的文本颜色	 	 	 	 	 	 	 
 stl_defaultTabTextSize	默认包含的选项卡的文本布局填充	 	 	 	 	 	 
 stl_defaultTabTextHorizontalPadding	默认包含的选项卡的文本布局填充	 	 	 	 	 	 
 stl_defaultTabTextMinWidth	标签最小宽度	 	 	 	 	 	 	 	 
 stl_customTabTextLayoutId	布局ID定义自定义标签。如果没有指定布局，请使用默认选项卡	 	 	 	 
 stl_customTabTextViewId	自定义选项卡布局中的文本视图id。如果你不确定customtabtextlayoutid，不工作	 	 
 stl_distributeEvenly	如果设置为true，每个选项卡得到相同的重量，默认FALSE	 	 	 	 
 stl_clickable	如果设置为false，禁用选项卡单击的选择，默认为true	 	 	 	 	 
 stl_titleOffset	如果设置为“auto_center，滑块位置的标签中会不断向中心。如果指定一个维度将它从左边偏移，默认24dp
 stl_drawDecorationAfterTab
 ---------------------





 *
 *
 */
public class SmartTabLayoutExtend extends HorizontalScrollView{

  private static final boolean DEFAULT_DISTRIBUTE_EVENLY = false;
  private static final int TITLE_OFFSET_DIPS = 24;
  private static final int TITLE_OFFSET_AUTO_CENTER = -1;
  private static final int TAB_VIEW_PADDING_DIPS = 16;
  private static final boolean TAB_VIEW_TEXT_ALL_CAPS = true;
  private static final int TAB_VIEW_TEXT_SIZE_SP = 12;
  private static final int TAB_VIEW_TEXT_COLOR = 0xFC000000;
  private static final int TAB_VIEW_TEXT_MIN_WIDTH = 0;
  private static final boolean TAB_CLICKABLE = true;

  private LinearLayout leftLl;
  protected final SmartTabStrip tabStrip;
  private int titleOffset;
  private int tabViewBackgroundResId;
  private boolean tabViewTextAllCaps;
  private ColorStateList tabViewTextColors;
  private float tabViewTextSize;
  private int tabViewTextHorizontalPadding;
  private int tabViewTextMinWidth;
  private ViewPager viewPager;
  private ViewPager.OnPageChangeListener viewPagerPageChangeListener;
  private OnScrollChangeListener onScrollChangeListener;
  private TabProvider tabProvider;
  private InternalTabClickListener internalTabClickListener;
  private NoViewPagerTabClickListener noViewPagerTabClickListener;
  private OnTabClickListener onTabClickListener;
  private boolean distributeEvenly;
  private float density;
  private int noViewPagerItemCount = 0;

  public SmartTabLayoutExtend(Context context) {
    this(context, null);
  }

  public SmartTabLayoutExtend(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public SmartTabLayoutExtend(Context context, AttributeSet attrs, int defStyle) {
    super(context, attrs, defStyle);

    // Disable the Scroll Bar
    setHorizontalScrollBarEnabled(false);

    final DisplayMetrics dm = getResources().getDisplayMetrics();
    density = dm.density;

    int tabBackgroundResId = NO_ID;
    boolean textAllCaps = TAB_VIEW_TEXT_ALL_CAPS;
    ColorStateList textColors;
    float textSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, TAB_VIEW_TEXT_SIZE_SP, dm);
    int textHorizontalPadding = (int) (TAB_VIEW_PADDING_DIPS * density);
    int textMinWidth = (int) (TAB_VIEW_TEXT_MIN_WIDTH * density);
    boolean distributeEvenly = DEFAULT_DISTRIBUTE_EVENLY;
    int customTabLayoutId = NO_ID;
    int customTabTextViewId = NO_ID;
    boolean clickable = TAB_CLICKABLE;
    int titleOffset = (int) (TITLE_OFFSET_DIPS * density);

    TypedArray a = context.obtainStyledAttributes(
            attrs, R.styleable.stl_SmartTabLayout, defStyle, 0);
    tabBackgroundResId = a.getResourceId(
            R.styleable.stl_SmartTabLayout_stl_defaultTabBackground, tabBackgroundResId);
    textAllCaps = a.getBoolean(
            R.styleable.stl_SmartTabLayout_stl_defaultTabTextAllCaps, textAllCaps);
    textColors = a.getColorStateList(
            R.styleable.stl_SmartTabLayout_stl_defaultTabTextColor);
    textSize = a.getDimension(
            R.styleable.stl_SmartTabLayout_stl_defaultTabTextSize, textSize);
    textHorizontalPadding = a.getDimensionPixelSize(
            R.styleable.stl_SmartTabLayout_stl_defaultTabTextHorizontalPadding, textHorizontalPadding);
    textMinWidth = a.getDimensionPixelSize(
            R.styleable.stl_SmartTabLayout_stl_defaultTabTextMinWidth, textMinWidth);
    customTabLayoutId = a.getResourceId(
            R.styleable.stl_SmartTabLayout_stl_customTabTextLayoutId, customTabLayoutId);
    customTabTextViewId = a.getResourceId(
            R.styleable.stl_SmartTabLayout_stl_customTabTextViewId, customTabTextViewId);
    distributeEvenly = a.getBoolean(
            R.styleable.stl_SmartTabLayout_stl_distributeEvenly, distributeEvenly);
    clickable = a.getBoolean(
            R.styleable.stl_SmartTabLayout_stl_clickable, clickable);
    titleOffset = a.getLayoutDimension(
            R.styleable.stl_SmartTabLayout_stl_titleOffset, titleOffset);
    a.recycle();

    this.titleOffset = titleOffset;
    this.tabViewBackgroundResId = tabBackgroundResId;
    this.tabViewTextAllCaps = textAllCaps;
    this.tabViewTextColors = (textColors != null)
            ? textColors
            : ColorStateList.valueOf(TAB_VIEW_TEXT_COLOR);
    this.tabViewTextSize = textSize;
    this.tabViewTextHorizontalPadding = textHorizontalPadding;
    this.tabViewTextMinWidth = textMinWidth;
    this.internalTabClickListener = clickable ? new InternalTabClickListener() : null;
    this.noViewPagerTabClickListener = clickable ? new NoViewPagerTabClickListener() : null;
    this.distributeEvenly = distributeEvenly;

    if (customTabLayoutId != NO_ID) {
      setCustomTabView(customTabLayoutId, customTabTextViewId);
    }

    this.tabStrip = new SmartTabStrip(context, attrs);
    //tabStrip.setBackgroundColor(Color.parseColor("#ff0000"));

    if (distributeEvenly && tabStrip.isIndicatorAlwaysInCenter()) {
      throw new UnsupportedOperationException(
              "'distributeEvenly' and 'indicatorAlwaysInCenter' both use does not support");
    }

    // Make sure that the Tab Strips fills this View
    setFillViewport(!tabStrip.isIndicatorAlwaysInCenter());

    LinearLayout linearLayout = new LinearLayout(context);
    linearLayout.setOrientation(LinearLayout.HORIZONTAL);
    addView(linearLayout, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    leftLl = new LinearLayout(context);
    leftLl.setOrientation(LinearLayout.HORIZONTAL);
    linearLayout.addView(leftLl, LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
    linearLayout.addView(tabStrip, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
  }
  private InternalEnableTabClickListener internalEnableTabClickListener;

  public LinearLayout getLeftLl(){
    return leftLl;
  }

  public void setIndicatorWidth(int indicatorWidthDp){
    if(tabStrip != null){
      tabStrip.indicatorWidth = (int)(indicatorWidthDp * density);
    }
  }

  public void setIndicatorThickness(int indicatorThicknessDp){
    if(tabStrip != null){
      tabStrip.indicatorThickness = (int)(indicatorThicknessDp * density);
    }
  }

  @Override
  protected void onScrollChanged(int l, int t, int oldl, int oldt) {
    super.onScrollChanged(l, t, oldl, oldt);
    if (onScrollChangeListener != null) {
      onScrollChangeListener.onScrollChanged(l, oldl);
    }
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    if (tabStrip.isIndicatorAlwaysInCenter() && tabStrip.getChildCount() > 0) {
      View firstTab = tabStrip.getChildAt(0);
      View lastTab = tabStrip.getChildAt(tabStrip.getChildCount() - 1);
      int start = (w - Utils.getMeasuredWidth(firstTab)) / 2 - Utils.getMarginStart(firstTab);
      int end = (w - Utils.getMeasuredWidth(lastTab)) / 2 - Utils.getMarginEnd(lastTab);
      tabStrip.setMinimumWidth(tabStrip.getMeasuredWidth());
      ViewCompat.setPaddingRelative(this, start, getPaddingTop(), end, getPaddingBottom());
      setClipToPadding(false);
    }
  }

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    super.onLayout(changed, l, t, r, b);
    // Ensure first scroll
    if (changed && viewPager != null) {
      scrollToTab(viewPager.getCurrentItem(), 0);
    }
  }

  /**
   * Set the behavior of the Indicator scrolling feedback.
   *
   * @param interpolator {@link SmartTabIndicationInterpolator}
   */
  public void setIndicationInterpolator(SmartTabIndicationInterpolator interpolator) {
    tabStrip.setIndicationInterpolator(interpolator);
  }

  public SmartTabStrip getSmartTabStrip(){
    return tabStrip;
  }

  /**
   * Set the custom {@link TabColorizer} to be used.
   *
   * If you only require simple customisation then you can use
   * {@link #setSelectedIndicatorColors(int...)} and {@link #setDividerColors(int...)} to achieve
   * similar effects.
   */
  public void setCustomTabColorizer(SmartTabLayout.TabColorizer tabColorizer) {
    tabStrip.setCustomTabColorizer(tabColorizer);
  }

  /**
   * Set the color used for styling the tab text. This will need to be called prior to calling
   * {@link #setViewPager(ViewPager)} otherwise it will not get set
   *
   * @param color to use for tab text
   */
  public void setDefaultTabTextColor(int color) {
    tabViewTextColors = ColorStateList.valueOf(color);
  }

  /**
   * Sets the colors used for styling the tab text. This will need to be called prior to calling
   * {@link #setViewPager(ViewPager)} otherwise it will not get set
   *
   * @param colors ColorStateList to use for tab text
   */
  public void setDefaultTabTextColor(ColorStateList colors) {
    tabViewTextColors = colors;
  }

  /**
   * Set the same weight for tab
   */
  public void setDistributeEvenly(boolean distributeEvenly) {
    this.distributeEvenly = distributeEvenly;
  }

  /**
   * Sets the colors to be used for indicating the selected tab. These colors are treated as a
   * circular array. Providing one color will mean that all tabs are indicated with the same color.
   */
  public void setSelectedIndicatorColors(int... colors) {
    tabStrip.setSelectedIndicatorColors(colors);
  }

  /**
   * Sets the colors to be used for tab dividers. These colors are treated as a circular array.
   * Providing one color will mean that all tabs are indicated with the same color.
   */
  public void setDividerColors(int... colors) {
    tabStrip.setDividerColors(colors);
  }

  /**
   * Set the {@link ViewPager.OnPageChangeListener}. When using {@link SmartTabLayoutExtend} you are
   * required to set any {@link ViewPager.OnPageChangeListener} through this method. This is so
   * that the layout can update it's scroll position correctly.
   *
   * @see ViewPager#setOnPageChangeListener(ViewPager.OnPageChangeListener)
   */
  public void setOnPageChangeListener(ViewPager.OnPageChangeListener listener) {
    viewPagerPageChangeListener = listener;
  }

  /**
   * Set {@link OnScrollChangeListener} for obtaining values of scrolling.
   *
   * @param listener the {@link OnScrollChangeListener} to set
   */
  public void setOnScrollChangeListener(OnScrollChangeListener listener) {
    onScrollChangeListener = listener;
  }

  /**
   * Set {@link OnTabClickListener} for obtaining click event.
   *
   * @param listener the {@link OnTabClickListener} to set
   */
  public void setOnTabClickListener(OnTabClickListener listener) {
    onTabClickListener = listener;
  }

  /**
   * Set the custom layout to be inflated for the tab views.
   *
   * @param layoutResId Layout id to be inflated
   * @param textViewId id of the {@link TextView} in the inflated view
   */
  public void setCustomTabView(int layoutResId, int textViewId) {
    tabProvider = new SimpleTabProvider(getContext(), layoutResId, textViewId);
  }

  /**
   * Set the custom layout to be inflated for the tab views.
   *
   * @param provider {@link TabProvider}
   */
  public void setCustomTabView(TabProvider provider) {
    tabProvider = provider;
  }

  /**
   * Sets the associated view pager. Note that the assumption here is that the pager content
   * (number of tabs and tab titles) does not change after this call has been made.
   */
  public void setViewPager(ViewPager viewPager) {
    setViewPager(viewPager , true);
  }

  public void setViewPager(ViewPager viewPager , boolean normal) {
    tabStrip.removeAllViews();
    if (!normal) {
      List<Integer> integerList = new ArrayList<>();
      integerList.add(0);
      integerList.add(1);
      internalEnableTabClickListener = new InternalEnableTabClickListener(integerList);
    }
    this.viewPager = viewPager;
    if (viewPager != null && viewPager.getAdapter() != null) {
      viewPager.addOnPageChangeListener(new InternalViewPagerListener());
      populateTabStrip(normal);
    }
  }

  public void setViewPager(ViewPager viewPager , boolean normal,boolean isClear) {
    if(isClear){
      tabStrip.removeAllViews();
    }
    if (!normal) {
      List<Integer> integerList = new ArrayList<>();
      integerList.add(0);
      integerList.add(1);
      internalEnableTabClickListener = new InternalEnableTabClickListener(integerList);
    }
    this.viewPager = viewPager;
    if (viewPager != null && viewPager.getAdapter() != null) {
      viewPager.addOnPageChangeListener(new InternalViewPagerListener());
      populateTabStrip(normal);
    }
  }

  /**
   * Returns the view at the specified position in the tabs.
   *
   * @param position the position at which to get the view from
   * @return the view at the specified position or null if the position does not exist within the
   * tabs
   */
  public View getTabAt(int position) {
    return tabStrip.getChildAt(position);
  }

  /**
   * Create a default view to be used for tabs. This is called if a custom tab view is not set via
   * {@link #setCustomTabView(int, int)}.
   */
  protected TextView createDefaultTabView(CharSequence title) {
    TextView textView = new TextView(getContext());
    textView.setGravity(Gravity.CENTER);
    textView.setText(title);
    textView.setTextColor(tabViewTextColors);
    textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, tabViewTextSize);
    textView.setTypeface(Typeface.DEFAULT_BOLD);
    textView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));

    if (tabViewBackgroundResId != NO_ID) {
      textView.setBackgroundResource(tabViewBackgroundResId);
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
      // If we're running on Honeycomb or newer, then we can use the Theme's
      // selectableItemBackground to ensure that the View has a pressed state
      TypedValue outValue = new TypedValue();
      getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
              outValue, true);
      textView.setBackgroundResource(outValue.resourceId);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
      // If we're running on ICS or newer, enable all-caps to match the Action Bar tab style
      //textView.setAllCaps(tabViewTextAllCaps);//英文会大写
    }

    textView.setPadding(
            tabViewTextHorizontalPadding, 0,
            tabViewTextHorizontalPadding, 0);

    if (tabViewTextMinWidth > 0) {
      textView.setMinWidth(tabViewTextMinWidth);
    }

    return textView;
  }

  protected FrameLayout createVipTabView(CharSequence title){
    FrameLayout frameLayout = (FrameLayout) LayoutInflater.from(getContext()).inflate(R.layout.vip_tab_view , null);
    TextView textView = frameLayout.findViewById(R.id.tab_tv);
    ImageView vip_tag = frameLayout.findViewById(R.id.vip_tag);
    textView.setGravity(Gravity.CENTER);
    textView.setText(title);
    textView.setTextColor(tabViewTextColors);
    textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, tabViewTextSize);
    textView.setTypeface(Typeface.DEFAULT_BOLD);
    textView.setLayoutParams(new LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    if (tabViewBackgroundResId != NO_ID) {
      textView.setBackgroundResource(tabViewBackgroundResId);
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
      // If we're running on Honeycomb or newer, then we can use the Theme's
      // selectableItemBackground to ensure that the View has a pressed state
      TypedValue outValue = new TypedValue();
      getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
              outValue, true);
      textView.setBackgroundResource(outValue.resourceId);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
      // If we're running on ICS or newer, enable all-caps to match the Action Bar tab style
      //textView.setAllCaps(tabViewTextAllCaps);//英文会大写
    }

    textView.setPadding(
            tabViewTextHorizontalPadding, 0,
            tabViewTextHorizontalPadding, 0);
    textView.setBackgroundColor(Color.TRANSPARENT);
    vip_tag.setBackgroundColor(Color.TRANSPARENT);
    frameLayout.setBackgroundColor(Color.TRANSPARENT);
    if (tabViewTextMinWidth > 0) {
      textView.setMinWidth(tabViewTextMinWidth);
    }
    frameLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
//    frameLayout.setBackground(null);
    return frameLayout;
  }


  public void addCustomTab(CharSequence charSequence){
    if(charSequence == null)return;
    View tabView = createDefaultTabView(charSequence);

    if (tabView == null) {
      throw new IllegalStateException("tabView is null.");
    }
    tabView.setBackgroundColor(0x00000000);//--

    if (distributeEvenly) {
      LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tabView.getLayoutParams();
      lp.width = 0;
      lp.weight = 1;
    }

    if (noViewPagerTabClickListener != null) {
      tabView.setOnClickListener(noViewPagerTabClickListener);
    }

    tabStrip.addView(tabView);

/*    if (i == viewPager.getCurrentItem()) {
      tabView.setSelected(true);
    }*/

    noViewPagerItemCount++;
  }

  private void populateTabStrip(boolean normal) {
    final PagerAdapter adapter = viewPager.getAdapter();

    for (int i = 0; i < adapter.getCount(); i++) {

      final View tabView = (tabProvider == null)
              ? !normal && i == adapter.getCount() - 1 ? createVipTabView(adapter.getPageTitle(i)) :
              createDefaultTabView(adapter.getPageTitle(i))
              : tabProvider.createTabView(tabStrip, i, adapter);
      m.a();

      if (tabView == null) {
        throw new IllegalStateException("tabView is null.");
      }
      tabView.setBackgroundColor(0x00000000);//--
//      tabView.setBackground(null);

      if (distributeEvenly) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tabView.getLayoutParams();
        lp.width = 0;
        lp.weight = 1;
      }
//      if (normal) {
      if (internalTabClickListener != null) {
        tabView.setOnClickListener(internalTabClickListener);
      }
//      } else {
//        if (internalEnableTabClickListener!= null) {
//          tabView.setOnClickListener(internalEnableTabClickListener);
//        }
//      }

      tabStrip.addView(tabView);

      //最后一个距右的距离
      if(i == adapter.getCount() -1 && lastItemMarginR > 0){
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) tabView.getLayoutParams();
        params.rightMargin = lastItemMarginR;
      }

      if (i == viewPager.getCurrentItem()) {
        tabView.setSelected(true);
      }

    }
  }

  int lastItemMarginR;
  public void setLastItemMarginRight(int marginR){
    lastItemMarginR = marginR;
  }

  private void scrollToTab(int tabIndex, float positionOffset) {
    final int tabStripChildCount = tabStrip.getChildCount();
    if (tabStripChildCount == 0 || tabIndex < 0 || tabIndex >= tabStripChildCount) {
      return;
    }

    final boolean isLayoutRtl = Utils.isLayoutRtl(this);
    View selectedTab = tabStrip.getChildAt(tabIndex);
    int widthPlusMargin = Utils.getWidth(selectedTab) + Utils.getMarginHorizontally(selectedTab);
    int extraOffset = (int) (positionOffset * widthPlusMargin);

    if (tabStrip.isIndicatorAlwaysInCenter()) {

      if (0f < positionOffset && positionOffset < 1f) {
        View nextTab = tabStrip.getChildAt(tabIndex + 1);
        int selectHalfWidth = Utils.getWidth(selectedTab) / 2 + Utils.getMarginEnd(selectedTab);
        int nextHalfWidth = Utils.getWidth(nextTab) / 2 + Utils.getMarginStart(nextTab);
        extraOffset = Math.round(positionOffset * (selectHalfWidth + nextHalfWidth));
      }

      View firstTab = tabStrip.getChildAt(0);
      int x;
      if (isLayoutRtl) {
        int first = Utils.getWidth(firstTab) + Utils.getMarginEnd(firstTab);
        int selected = Utils.getWidth(selectedTab) + Utils.getMarginEnd(selectedTab);
        x = Utils.getEnd(selectedTab) - Utils.getMarginEnd(selectedTab) - extraOffset;
        x -= (first - selected) / 2;
      } else {
        int first = Utils.getWidth(firstTab) + Utils.getMarginStart(firstTab);
        int selected = Utils.getWidth(selectedTab) + Utils.getMarginStart(selectedTab);
        x = Utils.getStart(selectedTab) - Utils.getMarginStart(selectedTab) + extraOffset;
        x -= (first - selected) / 2;
      }

      scrollTo(x, 0);
      return;

    }

    int x;
    if (titleOffset == TITLE_OFFSET_AUTO_CENTER) {

      if (0f < positionOffset && positionOffset < 1f) {
        View nextTab = tabStrip.getChildAt(tabIndex + 1);
        int selectHalfWidth = Utils.getWidth(selectedTab) / 2 + Utils.getMarginEnd(selectedTab);
        int nextHalfWidth = Utils.getWidth(nextTab) / 2 + Utils.getMarginStart(nextTab);
        extraOffset = Math.round(positionOffset * (selectHalfWidth + nextHalfWidth));
      }

      if (isLayoutRtl) {
        x = -Utils.getWidthWithMargin(selectedTab) / 2 + getWidth() / 2;
        x -= Utils.getPaddingStart(this);
      } else {
        x = Utils.getWidthWithMargin(selectedTab) / 2 - getWidth() / 2;
        x += Utils.getPaddingStart(this);
      }

    } else {

      if (isLayoutRtl) {
        x = (tabIndex > 0 || positionOffset > 0) ? titleOffset : 0;
      } else {
        x = (tabIndex > 0 || positionOffset > 0) ? -titleOffset : 0;
      }

    }

    int start = Utils.getStart(selectedTab);
    int startMargin = Utils.getMarginStart(selectedTab);
    if (isLayoutRtl) {
      x += start + startMargin - extraOffset - getWidth() + Utils.getPaddingHorizontally(this);
    } else {
      x += start - startMargin + extraOffset;
    }

    scrollTo(x, 0);

  }

  /**
   * Allows complete control over the colors drawn in the tab layout. Set with
   * {@link #setCustomTabColorizer(TabColorizer)}.
   */
/*  public interface TabColorizer {

    *//**
     * @return return the color of the indicator used when {@code position} is selected.
     *//*
    int getIndicatorColor(int position);

    *//**
     * @return return the color of the divider drawn to the right of {@code position}.
     *//*
    int getDividerColor(int position);

  }*/

  /**
   * Interface definition for a callback to be invoked when the scroll position of a view changes.
   */
  public interface OnScrollChangeListener {

    /**
     * Called when the scroll position of a view changes.
     *
     * @param scrollX Current horizontal scroll origin.
     * @param oldScrollX Previous horizontal scroll origin.
     */
    void onScrollChanged(int scrollX, int oldScrollX);
  }

  /**
   * Interface definition for a callback to be invoked when a tab is clicked.
   */
  public interface OnTabClickListener {

    /**
     * Called when a tab is clicked.
     *
     * @param position tab's position
     */
    void onTabClicked(int position);
  }

  /**
   * Create the custom tabs in the tab layout. Set with
   * {@link #setCustomTabView(TabProvider)}
   */
  public interface TabProvider {

    /**
     * @return Return the View of {@code position} for the Tabs
     */
    View createTabView(ViewGroup container, int position, PagerAdapter adapter);

  }

  private static class SimpleTabProvider implements TabProvider {

    private final LayoutInflater inflater;
    private final int tabViewLayoutId;
    private final int tabViewTextViewId;

    private SimpleTabProvider(Context context, int layoutResId, int textViewId) {
      inflater = LayoutInflater.from(context);
      tabViewLayoutId = layoutResId;
      tabViewTextViewId = textViewId;
    }

    @Override
    public View createTabView(ViewGroup container, int position, PagerAdapter adapter) {
      View tabView = null;
      TextView tabTitleView = null;

      if (tabViewLayoutId != NO_ID) {
        tabView = inflater.inflate(tabViewLayoutId, container, false);
      }

      if (tabViewTextViewId != NO_ID && tabView != null) {
        tabTitleView = (TextView) tabView.findViewById(tabViewTextViewId);
      }

      if (tabTitleView == null && TextView.class.isInstance(tabView)) {
        tabTitleView = (TextView) tabView;
      }

      if (tabTitleView != null) {
        tabTitleView.setText(adapter.getPageTitle(position));
      }

      return tabView;
    }

  }

  private class InternalViewPagerListener implements ViewPager.OnPageChangeListener {

    private int scrollState;

    @Override
    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
      position += noViewPagerItemCount;
      int tabStripChildCount = tabStrip.getChildCount();
      if ((tabStripChildCount == 0) || (position < 0) || (position >= tabStripChildCount)) {
        return;
      }

      tabStrip.onViewPagerPageChanged(position, positionOffset);

      scrollToTab(position, positionOffset);

      if (viewPagerPageChangeListener != null) {
        viewPagerPageChangeListener.onPageScrolled(position, positionOffset, positionOffsetPixels);
      }
    }

    @Override
    public void onPageScrollStateChanged(int state) {
      scrollState = state;

      if (viewPagerPageChangeListener != null) {
        viewPagerPageChangeListener.onPageScrollStateChanged(state);
      }
    }

    @Override
    public void onPageSelected(int position) {
      position += noViewPagerItemCount;
      if (scrollState == ViewPager.SCROLL_STATE_IDLE) {
        tabStrip.onViewPagerPageChanged(position, 0f);
        scrollToTab(position, 0);
      }

      for (int i = 0, size = tabStrip.getChildCount(); i < size; i++) {
        tabStrip.getChildAt(i).setSelected(position == i);
      }

      if (viewPagerPageChangeListener != null) {
        viewPagerPageChangeListener.onPageSelected(position);
      }
    }

  }

  private class InternalTabClickListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      for (int i = noViewPagerItemCount; i < tabStrip.getChildCount(); i++) {
        if (v == tabStrip.getChildAt(i)) {
          if (onTabClickListener != null) {
            onTabClickListener.onTabClicked(i);
          }
          viewPager.setCurrentItem(i-noViewPagerItemCount);//在viewPager前面有一些非viewPager的东西
          return;
        }
      }
    }
  }

  //为音乐写的莫种情况下 前两个标签无法点击的点击事件处理
  private class InternalEnableTabClickListener implements OnClickListener  {
    private List<Integer> enableIndex;

    public InternalEnableTabClickListener() {
    }

    public InternalEnableTabClickListener(List<Integer> enableIndex) {
      this.enableIndex = enableIndex;
    }

    @Override
    public void onClick(View v) {
      for (int i = 0; i < tabStrip.getChildCount(); i++) {
        if (v == tabStrip.getChildAt(i)) {
          if (onTabClickListener != null) {
            onTabClickListener.onTabClicked(i);
          }
          if (enableIndex != null) {
            if (enableIndex.contains(i)) {
              return;
            }
            viewPager.setCurrentItem(i);
          } else {
            viewPager.setCurrentItem(i);
          }
          return;
        }
      }
    }
  }

  private class NoViewPagerTabClickListener implements OnClickListener {
    @Override
    public void onClick(View v) {
      for (int i = 0; i < noViewPagerItemCount; i++) {
        if (v == tabStrip.getChildAt(i)) {
          if (onTabClickListener != null) {
            onTabClickListener.onTabClicked(i);
          }
          return;
        }
      }
    }
  }
}
