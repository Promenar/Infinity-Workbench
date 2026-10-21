package com.promenar.nexara.data.local.db.dao

import com.promenar.nexara.data.local.db.entity.MessageEntity
import io.mockk.coEvery

/** mockk 不执行接口默认方法；让模拟 DAO 的 [MessageDao.updateWith] 沿用已桩好的 getById 与 update。 */
fun MessageDao.stubUpdateWith() {
    val dao = this
    coEvery { dao.updateWith(any(), any()) } coAnswers {
        val existing = dao.getById(firstArg()) ?: return@coAnswers -1
        dao.update(secondArg<(MessageEntity) -> MessageEntity>()(existing))
    }
}
