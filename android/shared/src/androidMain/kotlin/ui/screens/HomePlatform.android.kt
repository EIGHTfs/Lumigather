// 传输层工厂的 Android 实现。
//
// 实现放 androidMain 是 KMP 的硬约束：commonMain 看不到 java.* / Android API，网络库只能落平台侧。
// 这里只是把已有的 OkHttp 实现（data/OkHttpHttpTransport.kt）暴露给 commonMain 的页面入口，
// 不做任何额外包装 —— 页面拿到的是同一个 HttpTransport 接口，将来换库只改这一个文件。
//
// 为什么不让 :app 直接注入：页面是 Kuikly 用 pageName 反射创建的，宿主没法在构造时塞参数；
// 走 expect/actual 能让"缺实现"在编译期就报错，而不是等到真机上看一片空白。

package ui.screens

import com.eightfs.lumigather.data.HttpTransport
import com.eightfs.lumigather.data.OkHttpHttpTransport

actual fun createDefaultHttpTransport(): HttpTransport = OkHttpHttpTransport()
