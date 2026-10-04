package io.github.hecate2.D7

/**
 * 标记只在模拟器上跑的多语言用例。
 *
 * 为什么要有这么一个注解：多语言用例要连开八遍 Activity、每种语言量一遍像素，是全套里
 * 最慢的一段（实测 8.3s / 54 个用例里占 14%），而它们要验的是「译文在窄屏上放不放得下」
 * ——同一段代码换个屏幕密度、换个系统语言就可能表现不同，真机（vivo，窄且高）上再跑一遍
 * 既慢又不能证明什么。所以默认在真机上整类跳过，只在模拟器上跑。
 *
 * 用注解而不是 `-e class` 白名单：以后新加多语言用例只标一下就自动进 fast 的排除名单，
 * 不必回头改脚本。
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class NeedsI18n