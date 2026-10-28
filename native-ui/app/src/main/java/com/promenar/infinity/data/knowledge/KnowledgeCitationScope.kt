package com.promenar.infinity.data.knowledge

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 为自动注入传递本轮取号函数，不改变检索接口或持久数据模型。 */
class KnowledgeCitationScope(val citation: (String) -> String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<KnowledgeCitationScope>
}
