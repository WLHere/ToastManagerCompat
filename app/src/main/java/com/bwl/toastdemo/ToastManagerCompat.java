package com.bwl.toastdemo;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import java.lang.ref.SoftReference;
import java.util.LinkedList;

/**
 * Toast Manager Compat。解决关闭通知权限无法显示toast的问题。
 * Created by baiwenlong on 1/8/21.
 */
public class ToastManagerCompat {
    private static volatile ToastAction sToastAction;
    private static boolean sInitialized = false;
    private static final Handler sUIHandler = new Handler(Looper.getMainLooper());

    /**
     * 初始化
     *
     * @param application
     */
    public static void init(@NonNull Application application) {
        if (!sInitialized) {
            synchronized (ToastManagerCompat.class) {
                if (!sInitialized) {
                    sInitialized = true;
                    if (isUseSystemToast(application)) {
                        // 使用系统toast
                        sToastAction = new SystemToastAction();
                    } else {
                        // 监听栈顶activity，供CompatToastAction使用
                        TopActivityMonitor.getInstance().init(application);
                        // 使用自定义toast
                        sToastAction = new CompatToastAction();
                    }
                }
            }
        }
    }

    public static Toast show(Toast toast) {
        if (toast == null) {
            return null;
        }
        if (!isUIThread()) {
            sUIHandler.post(() -> show(toast));
            return toast;
        }
        sToastAction.show(toast);
        return toast;
    }

    public static Toast cancel(Toast toast) {
        if (toast == null) {
            return null;
        }
        if (!isUIThread()) {
            sUIHandler.post(() -> cancel(toast));
            return toast;
        }
        sToastAction.cancel(toast);
        return toast;
    }

    public interface ToastAction {
        void show(@NonNull Toast toast);

        void cancel(@NonNull Toast toast);
    }

    /**
     * 系统toast
     */
    private static class SystemToastAction implements ToastAction {
        @Override
        public void show(@NonNull Toast toast) {
            toast.show();
        }

        @Override
        public void cancel(@NonNull Toast toast) {
            toast.cancel();
        }
    }

    /**
     * 兼容Toast action
     */
    private static class CompatToastAction implements ToastAction {

        public CompatToastAction() {
        }

        @Override
        public void show(@NonNull Toast toast) {
            LocalToastManager.getInstance().enqueueToast(toast);
        }

        @Override
        public void cancel(@NonNull Toast toast) {
            LocalToastManager.getInstance().cancelToast(toast);
        }
    }

    /**
     * LocalToastManagerService：负责toast排序、展示、消失
     */
    private static class LocalToastManager {
        private final LinkedList<ToastWrapper> mToastQueue = new LinkedList<>();
        private static final int MSG_ENQUEUE_TOAST = 1;
        private static final int MSG_CANCEL_TOAST = 2;
        private static final int MSG_ADD_TO_WINDOW = 3;
        private static final int MSG_REMOVE_FROM_WINDOW = 4;
        private static final int DELAY_TIMEOUT = 200;

        private static LocalToastManager sInstance = new LocalToastManager();

        private LocalToastManager() {
        }

        public static LocalToastManager getInstance() {
            return sInstance;
        }

        private Handler mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                switch (msg.what) {
                    case MSG_ENQUEUE_TOAST: {
                        Toast toast = (Toast) msg.obj;
                        ToastWrapper toastWrapper = addToQueueIfNotExists(toast);
                        if (mToastQueue.indexOf(toastWrapper) == 0) {
                            // 显示队列首位的toast
                            if (toastWrapper.isAddedToWindow()) {
                                // 从窗口移除
                                removeMessages(MSG_REMOVE_FROM_WINDOW, toastWrapper);
                                sendMessage(obtainMessage(MSG_REMOVE_FROM_WINDOW, toastWrapper));
                            }
                            // 添加到窗口
                            removeMessages(MSG_ADD_TO_WINDOW, toastWrapper);
                            sendMessage(obtainMessage(MSG_ADD_TO_WINDOW, toastWrapper));
                        }
                        break;
                    }
                    case MSG_CANCEL_TOAST: {
                        Toast toast = (Toast) msg.obj;
                        ToastWrapper toastWrapper = removeFromQueue(toast);
                        if (toastWrapper != null) {
                            // 从窗口移除
                            removeMessages(MSG_REMOVE_FROM_WINDOW, toastWrapper);
                            sendMessage(obtainMessage(MSG_REMOVE_FROM_WINDOW, toastWrapper));
                        }
                        break;
                    }
                    case MSG_ADD_TO_WINDOW: {
                        ToastWrapper toastWrapper = (ToastWrapper) msg.obj;
                        // 添加到窗口
                        toastWrapper.addToWindow();
                        // 延时从窗口移除
                        sendMessageDelayed(obtainMessage(MSG_REMOVE_FROM_WINDOW, toastWrapper), toastWrapper.isAddedToWindow() ? toastWrapper.getDuration() : 0);
                        break;
                    }
                    case MSG_REMOVE_FROM_WINDOW: {
                        ToastWrapper toastWrapper = (ToastWrapper) msg.obj;
                        mToastQueue.remove(toastWrapper);
                        if (toastWrapper.isAddedToWindow()) {
                            toastWrapper.removeFromWindow();
                        }
                        // 显示剩下的toast
                        showNextToast();
                        break;
                    }
                }
            }
        };

        /**
         * 排队显示toast
         *
         * @param toast
         */
        public void enqueueToast(@NonNull Toast toast) {
            // 延迟一段时间之后再执行，因为在没有通知栏权限的情况下，Toast 只能显示当前 Activity
            // 如果当前 Activity 在 ToastUtils.show 之后进行 finish 了，那么这个时候 Toast 可能会显示不出来
            // 因为 Toast 会显示在销毁 Activity 界面上，而不会显示在新跳转的 Activity 上面
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_ENQUEUE_TOAST, toast), DELAY_TIMEOUT);
        }

        /**
         * 取消toast的显示
         *
         * @param toast
         */
        public void cancelToast(@NonNull Toast toast) {
            // 和显示使用一样的延迟时间，可以避免顺混乱。
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_CANCEL_TOAST, toast), DELAY_TIMEOUT);
        }

        /**
         * 添加到队列
         *
         * @param toast
         * @return toast包装类
         */
        @NonNull
        private ToastWrapper addToQueueIfNotExists(Toast toast) {
            for (int i = 0; i < mToastQueue.size(); i++) {
                if (mToastQueue.get(i).getToast() == toast) {
                    return mToastQueue.get(i);
                }
            }
            ToastWrapper toastWrapper = new ToastWrapper(toast);
            mToastQueue.add(toastWrapper);
            return toastWrapper;
        }

        /**
         * 从队列中删除
         *
         * @param toast
         * @return toast包装类
         */
        @Nullable
        private ToastWrapper removeFromQueue(Toast toast) {
            for (int i = 0; i < mToastQueue.size(); i++) {
                if (mToastQueue.get(i).getToast() == toast) {
                    return mToastQueue.remove(i);
                }
            }
            return null;
        }

        private void showNextToast() {
            if (mToastQueue.size() > 0 && !mToastQueue.get(0).isAddedToWindow()) {
                mHandler.removeMessages(MSG_ADD_TO_WINDOW, mToastQueue.get(0));
                mHandler.sendMessage(mHandler.obtainMessage(MSG_ADD_TO_WINDOW, mToastQueue.get(0)));
            }
        }
    }

    private static class ToastWrapper {
        private final Toast mToast;
        private SoftReference<Activity> mBoundActivity;
        private SoftReference<View> mBoundToastView;
        private boolean mIsAddedToWindow = false;

        public ToastWrapper(Toast toast) {
            this.mToast = toast;
        }

        public void addToWindow() {
            Activity topActivity = TopActivityMonitor.getInstance().getTopActivity();
            if (topActivity == null || topActivity.isFinishing()) {
                return;
            }
            mIsAddedToWindow = true;
            final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
            params.format = PixelFormat.TRANSLUCENT;
            params.windowAnimations = android.R.style.Animation_Toast;
            params.flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            params.packageName = topActivity.getPackageName();
            // 重新初始化位置
            params.gravity = mToast.getGravity();
            params.x = mToast.getXOffset();
            params.y = mToast.getYOffset();
            params.verticalMargin = mToast.getVerticalMargin();
            params.horizontalMargin = mToast.getHorizontalMargin();

            try {
                View toastView = mToast.getView();
                mBoundToastView = new SoftReference<>(toastView);
                mBoundActivity = new SoftReference(topActivity);

                WindowManager windowManager = (WindowManager) topActivity.getSystemService(Context.WINDOW_SERVICE);
                if (windowManager != null) {
                    windowManager.addView(toastView, params);
                }
                return;
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
        }

        public void removeFromWindow() {
            mIsAddedToWindow = false;
            Activity boundActivity = mBoundActivity.get();
            View boundToastView = mBoundToastView.get();
            if (boundActivity == null || boundActivity.isFinishing() || boundToastView == null) {
                return;
            }
            try {
                WindowManager windowManager = (WindowManager) boundActivity.getSystemService(Context.WINDOW_SERVICE);
                if (windowManager != null) {
                    windowManager.removeViewImmediate(boundToastView);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public boolean isAddedToWindow() {
            return mIsAddedToWindow;
        }

        public long getDuration() {
            if (mToast.getDuration() == Toast.LENGTH_LONG) {
                return 3000;
            } else {
                return 2000;
            }
        }

        public Toast getToast() {
            return mToast;
        }
    }

    /**
     * 记录栈顶activity
     */
    private static class TopActivityMonitor implements Application.ActivityLifecycleCallbacks {
        private Activity mTopActivity;

        private static TopActivityMonitor sInstance = new TopActivityMonitor();

        private TopActivityMonitor() {
        }

        public static TopActivityMonitor getInstance() {
            return sInstance;
        }

        public void init(Application application) {
            application.registerActivityLifecycleCallbacks(this);
        }

        public Activity getTopActivity() {
            return mTopActivity;
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            mTopActivity = activity;
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
            mTopActivity = activity;
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            mTopActivity = activity;
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            if (mTopActivity == activity) {
                mTopActivity = null;
            }
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {

        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {

        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {

        }
    }

    /**
     * 是否使用系统toast
     *
     * @param context
     * @return
     */
    private static boolean isUseSystemToast(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q// android在10.0修复了关闭通知权限后不显示toast的bug
                || NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    private static boolean isUIThread() {
        return Looper.getMainLooper() == Looper.myLooper();
    }
}
