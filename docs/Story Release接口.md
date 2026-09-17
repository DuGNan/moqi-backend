# Story Release 接口

## 适用范围

本文描述章节正文 revision、作品修订工作区和 Story Release 的当前后端契约。事实变化抽取、影响传播和前端修订工作区不在当前范围。

## 公共边界

- `chapter_prose_revisions` 的正文、正文哈希、父 revision 和来源创建后不可修改。
- revision 状态为 `draft -> reviewing -> confirmable -> published -> superseded`；未发布候选可进入 `abandoned`。
- `confirmable` 必须绑定同作品、同章节、整章、正文哈希一致的 #105 评价；评价 `generationId` 精确匹配修订的 `qualityGenerationId`，未创建独立质量快照时匹配原 `sourceGenerationId`。发布时重新校验最新评价仍为 `ready + pass/warning`。没有可追溯 generation 来源的修订不能借用其他候选的评价。
- `contentAssemblyMode=bounded_revision` 的 generation 必须同时提交对应的 `sourceBoundedRevisionId`；任务只有在其 `resultReportId` 指向同一 result generation 的 `ready + pass/warning` 报告、查询语义达到 `candidate_ready` 后才能创建和绑定 revision。`needs_human`、失败或仍在重新评价的任务不能借用 generation 的其他报告放行。
- 同一 #106 门禁也作用于兼容的 generation 采纳接口：最新整章报告必须就是唯一未删除 task 的 `resultReportId`，并精确匹配作品、章节、generation 和正文哈希；alternate latest report 不能绕过任务状态。
- 工作区固定创建时的 `baselineReleaseId`、作品版本和每个变更章节的当前 revision/章节版本。
- 发布与回退必须提交 `userConfirmed=true`、幂等键和乐观锁版本。
- 发布幂等重放必须命中原工作区的 `publishedReleaseId`；回退幂等重放必须同时匹配目标 release 和原父 release。跨工作区、跨操作类型或不同回退目标复用同一幂等键返回冲突。
- 回退不会重新激活旧 release，而是创建映射相同、带 `rollbackOfReleaseId` 的新 release，保留完整审计链。
- Story Release 是完整作品快照。回退目标缺少当前 release 中后来新增的章节时，该章节的公开 revision 指针和兼容正文投影以 CAS 方式清空，旧 revision 进入 `superseded`，章节记录本身仍保留。

## API

根路径：`/api/works/{workId}/story-revisions`

- `POST /chapters/{chapterId}/revisions`：创建不可变 revision draft。
- `GET /chapters/{chapterId}/revisions`：查询章节 revision 历史。
- `GET /chapters/{chapterId}/revisions/{revisionId}`：查询 revision。
- `GET /chapters/{chapterId}/revisions/{revisionId}/compare?baseRevisionId=`：返回两份不可变正文及哈希。
- `POST /chapters/{chapterId}/revisions/{revisionId}/evaluation`：绑定或刷新整章评价状态。
- `POST /chapters/{chapterId}/revisions/{revisionId}/abandon`：放弃未发布候选。
- `POST /workspaces`：从当前 Story Release 创建作品修订工作区。
- `GET /workspaces/{workspaceId}`：恢复工作区及阻塞项。
- `PUT /workspaces/{workspaceId}/chapters/{chapterId}`：加入待发布 revision。
- `POST /workspaces/{workspaceId}/prepare`：重新校验评价、哈希和基线，生成阻塞项。
- `POST /workspaces/{workspaceId}/publish`：用户确认后原子创建并切换 Story Release。
- `POST /workspaces/{workspaceId}/abandon`：放弃工作区，不修改正文或 release。
- `GET /releases`、`GET /releases/{releaseId}`：查询发布历史与冻结章节映射。
- `GET /releases/{releaseId}/compare?baseReleaseId=`：比较章节 revision 映射。
- `POST /releases/{releaseId}/rollback`：用户确认后以目标映射创建新的回退 release。

## 修订正文质量检查

`POST /chapters/{chapterId}/revisions/{revisionId}/quality-evaluation` 接收 `expectedVersion`。
手工修订首次检查时，事务内创建 `candidate_snapshot` generation，正文来自不可变 revision，
上下文复制原 generation 的冻结来源。`quality_generation_id` 只绑定一次，原 `source_generation_id`、
parent、正文和哈希保持不变。重复启动复用同一快照和报告。V55 新增可空且唯一的质量快照外键。

`GET` 同路径恢复最新报告，未启动的手工修订返回 null。报告继续使用整章评价的
`reportStatus/conclusion/currentAttempt/retryable/findings` 契约。
`POST .../quality-evaluation/{reportId}/retry` 接收 `expectedAttempt`，校验当前发布父版本、
修订状态、正文哈希、报告归属和最新失败步骤，复用原报告和运行任务。

通过后显式调用既有 `POST .../evaluation` 绑定该报告和当前 `expectedVersion`；
绑定与发布门禁精确校验质量快照 generation 及 revision 正文哈希。
未经独立评价的旧正文报告不能替代修改后正文的报告。未通过时保留修订草稿，
作者可以创建进一步修改的修订；评价操作不会发布正文或确认知识。

## 发布事务

发布事务依次写入新 release 和完整章节映射，按章节版本及旧 revision 指针切换变更章节或下线目标快照中缺席的章节，再按作品版本及旧 release 指针切换作品，最后更新新旧 release 和 revision 状态。任一步更新行数不为 1 都抛出冲突并回滚整个事务，不会产生半新半旧的公开作品。

`chapters.content` 是兼容旧读取接口的当前发布正文投影；拥有 `current_prose_revision_id` 后，旧正文保存 SQL 不再允许更新该字段。
