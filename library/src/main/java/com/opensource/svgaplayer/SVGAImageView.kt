package com.opensource.svgaplayer

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import java.net.URL

/**
 * Created by cuiminghui on 2017/3/29.
 */

class SVGADrawable(val videoItem: SVGAVideoEntity, val dynamicItem: SVGADynamicEntity): Drawable() {

    constructor(videoItem: SVGAVideoEntity): this(videoItem, SVGADynamicEntity())

    var cleared = true
        internal set (value) {
            if (field == value) {
                return
            }
            field = value
            invalidateSelf()
        }

    var currentFrame = 0
        internal set (value) {
            if (field == value) {
                return
            }
            field = value
            invalidateSelf()
        }

    var scaleType: ImageView.ScaleType = ImageView.ScaleType.MATRIX

    internal val drawer = SVGACanvasDrawer(videoItem, dynamicItem)

    override fun draw(canvas: Canvas?) {
        if (cleared) {
            return
        }
        canvas?.let {
            drawer.canvas = it
            drawer.drawFrame(currentFrame, scaleType)
        }
    }

    override fun setAlpha(alpha: Int) { }

    override fun getOpacity(): Int {
        return PixelFormat.OPAQUE
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {

    }

}

open class SVGAImageView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    enum class FillMode {
        Backward,
        Forward,
    }

    var loops = 0

    var clearsAfterStop = true

    var fillMode: FillMode = FillMode.Forward

    var callback: SVGACallback? = null

    private var animator: ValueAnimator? = null

    init {
        setSoftwareLayerType()
        attrs?.let { loadAttrs(it) }
    }


    private fun setSoftwareLayerType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            this.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator?.removeAllUpdateListeners()
    }

    fun loadAttrs(attrs: AttributeSet) {
        val typedArray = context.theme.obtainStyledAttributes(attrs, R.styleable.SVGAImageView, 0, 0)
        loops = typedArray.getInt(R.styleable.SVGAImageView_loopCount, 0)
        clearsAfterStop = typedArray.getBoolean(R.styleable.SVGAImageView_clearsAfterStop, true)
        val antiAlias = typedArray.getBoolean(R.styleable.SVGAImageView_antiAlias, false)
        val autoPlay = typedArray.getBoolean(R.styleable.SVGAImageView_autoPlay, true)
        typedArray.getString(R.styleable.SVGAImageView_source)?.let {
            loadSourceAsync(it, antiAlias, autoPlay)
        }
        typedArray.getString(R.styleable.SVGAImageView_fillMode)?.let {
            if (it == "0") {
                fillMode = FillMode.Backward
            }
            else if (it == "1") {
                fillMode = FillMode.Forward
            }
        }
    }

    /**
     * 异步加载动画资源
     */
    private fun loadSourceAsync(source: String, antiAlias: Boolean, autoPlay: Boolean) {
        val parser = SVGAParser(context)
        Thread({
            val completionHandler = object : SVGAParser.ParseCompletion {
                override fun onComplete(videoItem: SVGAVideoEntity) {
                    handleLoadedVideoEntity(videoItem, antiAlias, autoPlay)
                }
                override fun onError() {}
            }
            if (source.startsWith("http://") || source.startsWith("https://")) {
                val url = URL(source)
                parser.parse(url, completionHandler)
            }else{
                // NOTE 原代码这里不是在 else 分支看起来应该是 Bug
                parser.parse(source,completionHandler)
            }

        }).start()
    }

    /**
     * 处理加载回来的 VideoEntiry
     */
    private fun handleLoadedVideoEntity(videoItem: SVGAVideoEntity, antiAlias: Boolean, autoPlay: Boolean) {
        handler?.post {
            videoItem.antiAlias = antiAlias
            setVideoItem(videoItem)
            if (autoPlay) {
                startAnimation()
            }
        }
    }

    fun startAnimation() {
        val drawable = drawable as? SVGADrawable ?: return
        drawable.cleared = false
        drawable.scaleType = scaleType
        val videoItem = drawable.videoItem

        val animator = ValueAnimator.ofInt(0, videoItem.frames - 1)
        val durationScale = getValueAnimatorDurationScale() ?: 1.0
        animator.interpolator = LinearInterpolator()
        animator.duration = (videoItem.frames * (1000 / videoItem.FPS) / durationScale).toLong()
        animator.repeatCount = if (loops < 1) 99999 else loops - 1
        animator.addUpdateListener {
            drawable.currentFrame = animator.animatedValue as Int
            val percentage = (drawable.currentFrame + 1).toDouble() / videoItem.frames.toDouble()
            callback?.onStep(drawable.currentFrame, percentage)
        }
        animator.addListener(object : Animator.AnimatorListener {
            override fun onAnimationRepeat(animation: Animator?) {
                callback?.onRepeat()
            }
            override fun onAnimationEnd(animation: Animator?) {
                stopAnimation()
                if (!clearsAfterStop) {
                    if (fillMode == FillMode.Backward) {
                        drawable.currentFrame = 0
                    }
                }
                callback?.onFinished()
            }
            override fun onAnimationCancel(animation: Animator?) {}
            override fun onAnimationStart(animation: Animator?) {}
        })
        animator.start()
        this.animator = animator

    }

    /**
     * 通过反射读取 ValueAnimator#sDurationScale 静态变量的值
     */
    private fun getValueAnimatorDurationScale(): Double? {
        try {
            Class.forName("android.animation.ValueAnimator")?.let {
                it.getDeclaredField("sDurationScale")?.let {
                    it.isAccessible = true
                    return it.getFloat(Class.forName("android.animation.ValueAnimator")).toDouble()
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    fun pauseAnimation() {
        stopAnimation(false)
        callback?.onPause()
    }

    fun stopAnimation() {
        stopAnimation(clear = clearsAfterStop)
    }

    fun stopAnimation(clear: Boolean) {
        animator?.cancel()
        animator?.removeAllUpdateListeners()
        (drawable as? SVGADrawable)?.let {
            it.cleared = clear
        }
    }

    fun setVideoItem(videoItem: SVGAVideoEntity) {
        setVideoItem(videoItem, SVGADynamicEntity())
    }

    fun setVideoItem(videoItem: SVGAVideoEntity, dynamicItem: SVGADynamicEntity) {
        val drawable = SVGADrawable(videoItem, dynamicItem)
        drawable.cleared = clearsAfterStop
        setImageDrawable(drawable)
    }

    fun stepToFrame(frame: Int, andPlay: Boolean) {
        pauseAnimation()
        val drawable = drawable as? SVGADrawable ?: return
        drawable.currentFrame = frame
        if (andPlay) {
            startAnimation()
            animator?.let {
                val percentage = (frame.toFloat() / drawable.videoItem.frames.toFloat()).coerceIn(0.0f, 1.0f)
                it.currentPlayTime = (percentage * it.duration).toLong()
            }
        }
    }

    fun stepToPercentage(percentage: Double, andPlay: Boolean) {
        val drawable = drawable as? SVGADrawable ?: return
        var frame = (drawable.videoItem.frames * percentage).toInt()
        if (frame >= drawable.videoItem.frames && frame > 0) {
            frame = drawable.videoItem.frames - 1
        }
        stepToFrame(frame, andPlay)
    }

}