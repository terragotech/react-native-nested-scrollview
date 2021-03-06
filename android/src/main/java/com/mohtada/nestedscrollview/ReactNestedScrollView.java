/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.mohtada.nestedscrollview;

import javax.annotation.Nullable;

import java.lang.reflect.Field;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.support.v4.widget.NestedScrollView;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;
import android.widget.ScrollView;

import com.facebook.react.bridge.ReactContext;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.views.scroll.FpsListener;
import com.facebook.react.views.scroll.OnScrollDispatchHelper;
import com.facebook.react.uimanager.MeasureSpecAssertions;
import com.facebook.react.uimanager.events.NativeGestureUtil;
import com.facebook.react.uimanager.ReactClippingViewGroup;
import com.facebook.react.uimanager.ReactClippingViewGroupHelper;
import com.facebook.infer.annotation.Assertions;

/**
 * Forked from https://github.com/facebook/react-native/blob/v0.35.0/Libraries/Components/ScrollView/ScrollView.js
 *
 * A simple subclass of ScrollView that doesn't dispatch measure and layout to its children and has
 * a scroll listener to send scroll events to JS.
 *
 * <p>ReactScrollView only supports vertical scrolling. For horizontal scrolling,
 * use {@link ReactHorizontalScrollView}.
 */
public class ReactNestedScrollView extends NestedScrollView implements ReactClippingViewGroup {

    public static final String TAG = "ReactNestedScrollView";

    private static Field sScrollerField;
    private static boolean sTriedToGetScrollerField = false;

    private final OnScrollDispatchHelper mOnScrollDispatchHelper = new OnScrollDispatchHelper();
    private final OverScroller mScroller;

    private @Nullable Rect mClippingRect;
    private boolean mDoneFlinging;
    private boolean mDragging;
    private boolean mFlinging;
    private boolean mRemoveClippedSubviews;
    private boolean mScrollEnabled = true;
    private boolean mSendMomentumEvents;
    private @Nullable FpsListener mFpsListener = null;
    private @Nullable String mScrollPerfTag;
    private @Nullable Drawable mEndBackground;
    private int mEndFillColor = Color.TRANSPARENT;

    public ReactNestedScrollView(ReactContext context) {
        this(context, null);
    }

    public ReactNestedScrollView(ReactContext context, @Nullable FpsListener fpsListener) {
        super(context);
        this.setTag(TAG);
        mFpsListener = fpsListener;

        if (!sTriedToGetScrollerField) {
            sTriedToGetScrollerField = true;
            try {
                sScrollerField = NestedScrollView.class.getDeclaredField("mScroller");
                makeAccessible(sScrollerField);
            } catch (NoSuchFieldException e) {
                Log.w(
                    ReactConstants.TAG,
                    "Failed to get mScroller field for ScrollView! " +
                        "This app will exhibit the bounce-back scrolling bug :(");
            }
        }

        if (sScrollerField != null) {
            try {
                Object scroller = sScrollerField.get(this);
                if (scroller instanceof OverScroller) {
                    mScroller = (OverScroller) scroller;
                } else {
                    Log.w(
                        ReactConstants.TAG,
                        "Failed to cast mScroller field in ScrollView (probably due to OEM changes to AOSP)! " +
                            "This app will exhibit the bounce-back scrolling bug :(");
                    mScroller = null;
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to get mScroller from ScrollView!", e);
            }
        } else {
            mScroller = null;
        }
    }

    public void setSendMomentumEvents(boolean sendMomentumEvents) {
        mSendMomentumEvents = sendMomentumEvents;
    }

    public void setScrollPerfTag(String scrollPerfTag) {
        mScrollPerfTag = scrollPerfTag;
    }

    public void setScrollEnabled(boolean scrollEnabled) {
        mScrollEnabled = scrollEnabled;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        MeasureSpecAssertions.assertExplicitMeasureSpec(widthMeasureSpec, heightMeasureSpec);

        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // Call with the present values in order to re-layout if necessary
        scrollTo(getScrollX(), getScrollY());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mRemoveClippedSubviews) {
            updateClippingRect();
        }
    }

//    Not Working with NestedScrollView
//    @Override
//    protected void onAttachedToWindow() {
//        super.onAttachedToWindow();
//        if (mRemoveClippedSubviews) {
//            updateClippingRect();
//        }
//    }

    @Override
    protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);

        if (mOnScrollDispatchHelper.onScrollChanged(x, y)) {
            if (mRemoveClippedSubviews) {
                updateClippingRect();
            }

            if (mFlinging) {
                mDoneFlinging = false;
            }

            ReactNestedScrollViewHelper.emitScrollEvent(this);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!mScrollEnabled) {
            return false;
        }

        if (super.onInterceptTouchEvent(ev)) {
            NativeGestureUtil.notifyNativeGestureStarted(this, ev);
            ReactNestedScrollViewHelper.emitScrollBeginDragEvent(this);
            mDragging = true;
            enableFpsListener();
            return true;
        }

        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mScrollEnabled) {
            return false;
        }

        int action = ev.getAction() & MotionEvent.ACTION_MASK;
        if (action == MotionEvent.ACTION_UP && mDragging) {
            ReactNestedScrollViewHelper.emitScrollEndDragEvent(this);
            mDragging = false;
            disableFpsListener();
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public void setRemoveClippedSubviews(boolean removeClippedSubviews) {
        if (removeClippedSubviews && mClippingRect == null) {
            mClippingRect = new Rect();
        }
        mRemoveClippedSubviews = removeClippedSubviews;
        updateClippingRect();
    }

    @Override
    public boolean getRemoveClippedSubviews() {
        return mRemoveClippedSubviews;
    }

    @Override
    public void updateClippingRect() {
        if (!mRemoveClippedSubviews) {
            return;
        }

        Assertions.assertNotNull(mClippingRect);

        ReactClippingViewGroupHelper.calculateClippingRect(this, mClippingRect);
        View contentView = getChildAt(0);
        if (contentView instanceof ReactClippingViewGroup) {
            ((ReactClippingViewGroup) contentView).updateClippingRect();
        }
    }

    @Override
    public void getClippingRect(Rect outClippingRect) {
        outClippingRect.set(Assertions.assertNotNull(mClippingRect));
    }

    @Override
    public void fling(int velocityY) {
        if (mScroller != null) {
            // FB SCROLLVIEW CHANGE

            // We provide our own version of fling that uses a different call to the standard OverScroller
            // which takes into account the possibility of adding new content while the ScrollView is
            // animating. Because we give essentially no max Y for the fling, the fling will continue as long
            // as there is content. See #onOverScrolled() to see the second part of this change which properly
            // aborts the scroller animation when we get to the bottom of the ScrollView content.

            int scrollWindowHeight = getHeight() - getPaddingBottom() - getPaddingTop();

            mScroller.fling(
                getScrollX(),
                getScrollY(),
                0,
                velocityY,
                0,
                0,
                0,
                Integer.MAX_VALUE,
                0,
                scrollWindowHeight / 2);

            postInvalidateOnAnimation();

            // END FB SCROLLVIEW CHANGE
        } else {
            super.fling(velocityY);
        }

        if (mSendMomentumEvents || isScrollPerfLoggingEnabled()) {
            mFlinging = true;
            enableFpsListener();
            ReactNestedScrollViewHelper.emitScrollMomentumBeginEvent(this);
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    if (mDoneFlinging) {
                        mFlinging = false;
                        disableFpsListener();
                        ReactNestedScrollViewHelper.emitScrollMomentumEndEvent(ReactNestedScrollView.this);
                    } else {
                        mDoneFlinging = true;
                        ReactNestedScrollView.this.postOnAnimationDelayed(this, ReactNestedScrollViewHelper.MOMENTUM_DELAY);
                    }
                }
            };
            postOnAnimationDelayed(r, ReactNestedScrollViewHelper.MOMENTUM_DELAY);
        }
    }

    private void enableFpsListener() {
        if (isScrollPerfLoggingEnabled()) {
            Assertions.assertNotNull(mFpsListener);
            Assertions.assertNotNull(mScrollPerfTag);
            mFpsListener.enable(mScrollPerfTag);
        }
    }

    private void disableFpsListener() {
        if (isScrollPerfLoggingEnabled()) {
            Assertions.assertNotNull(mFpsListener);
            Assertions.assertNotNull(mScrollPerfTag);
            mFpsListener.disable(mScrollPerfTag);
        }
    }

    private boolean isScrollPerfLoggingEnabled() {
        return mFpsListener != null && mScrollPerfTag != null && !mScrollPerfTag.isEmpty();
    }

    @Override
    public void draw(Canvas canvas) {
        if (mEndFillColor != Color.TRANSPARENT) {
            final View content = getChildAt(0);
            if (mEndBackground != null && content != null && content.getBottom() < getHeight()) {
                mEndBackground.setBounds(0, content.getBottom(), getWidth(), getHeight());
                mEndBackground.draw(canvas);
            }
        }
        super.draw(canvas);
    }

    public void setEndFillColor(int color) {
        if (color != mEndFillColor) {
            mEndFillColor = color;
            mEndBackground = new ColorDrawable(mEndFillColor);
        }
    }

    @Override
    protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
        if (mScroller != null) {
            // FB SCROLLVIEW CHANGE

            // This is part two of the reimplementation of fling to fix the bounce-back bug. See #fling() for
            // more information.

            if (!mScroller.isFinished() && mScroller.getCurrY() != mScroller.getFinalY()) {
                int scrollRange = Math.max(
                    0,
                    getChildAt(0).getHeight() - (getHeight() - getPaddingBottom() - getPaddingTop()));
                if (scrollY >= scrollRange) {
                    mScroller.abortAnimation();
                }
            }

            // END FB SCROLLVIEW CHANGE
        }

        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY);
    }
    private void makeAccessible(Field field){
        field.setAccessible(true);
    }
}
