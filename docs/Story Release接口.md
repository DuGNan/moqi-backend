# Story Release 接口

## 适用范围

本文描述章节正文 revision、作品修订工作区和 Story Release 的当前后端契约。事实变化抽取、影响传播和前端修订工作区不在当前范围。

## 公共边界

- `chapter_prose_revisions` 的正文、正文哈希、父 revision 和来源创建后不可修改。
- revision 状态为 `draft -> reviewing -> confirmable -> published -> superseded`；未发布候选可进入 `abandoned`。
- `confirmable` 必须绑定同作品、同章节、整章、正文哈希一致且 `generationId` 精确等于 revision `sourceGenerationId` 的 #105 评价；发布时重新校验最新评价仍为 `ready + pass/warning`。没有来源 generation 的手工 revision 必须先形成独立 generation 与评价链，不能借用相同正文的其他 generation 报告。
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

## 数据库原子性

发布事务依次写入新 release 和完整章节映射，按章节版本及旧 revision 指针切换变更章节或下线目标快照中缺席的章节，再按作品版本及旧 release 指针切换作品，最后更新新旧 release 和 revision 状态。任一步更新行数不为 1 都抛出冲突并回滚整个事务，不会产生半新半旧的公开作品。

`chapters.content` 是兼容旧读取接口的当前发布正文投影；拥有 `current_prose_revision_id` 后，旧正文保存 SQL 不再允许更新该字段。
