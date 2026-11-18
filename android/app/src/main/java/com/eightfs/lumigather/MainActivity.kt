package com.eightfs.lumigather

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 安卓端唯一入口 Activity。
 *
 * 现在只做一件事：证明工具链跑通（能编译、能安装、能启动）。
 * 真正的三栏界面（网格 + 左右侧栏 + 底部状态栏）会由 Kuikly 页面承载，
 * 届时这里只负责初始化适配器并打开对应 pageName，不再自己拼任何界面。
 *
 * 之所以先用原生 TextView 而不是直接挂 Kuikly 容器：容器要有可打开的页面才能渲染，
 * 而业务层页面还没开始写；先让空壳可启动，避免「装上就打不开」的假象。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildPlaceholderView())
    }

    /** 构建占位界面：一行应用名 + 一行说明，用于人工确认 APK 装得上、起得来。 */
    private fun buildPlaceholderView(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#EBF1FE"))
        }

        container.addView(createText(getString(R.string.app_name), 22f, "#1E232E"))
        container.addView(createText("工程骨架已就绪", 13f, "#646974"))
        return container
    }

    /** 生成居中文本；字号单位 sp，颜色用十六进制串，避免这里再引入一套色值常量。 */
    private fun createText(content: String, sizeSp: Float, colorHex: String): TextView {
        return TextView(this).apply {
            text = content
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(Color.parseColor(colorHex))
            gravity = Gravity.CENTER
        }
    }
}
