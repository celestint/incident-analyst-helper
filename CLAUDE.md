# Incident Analyst Helper - 开发约定

## 项目定位

排障分析助手，运维人员触发后按手册逐步收集证据，实时展示分析过程，生成只读报告。

## 分支策略

- **dev**: 开发分支，所有功能开发在这里完成
- **master**: 稳定分支，合并自 dev，用于生产

**分支流程**:
1. 从 master 切出 dev 分支 (`git checkout -b dev`)
2. 在 dev 分支开发、测试
3. 合并到 master (`git checkout master && git merge dev`)

## Git 规范

- commit message 用英文，简洁描述变更意图
- **不自动 push**，只在需要跨设备同步时手动 push
- 通过 PR 或 merge 合并，不直接推送

## 开发规范

### 后端
- Java 17+ / Spring Boot
- Maven 构建
- 代码提交前运行测试 (`mvn test`)
- 密钥、token 不进代码

### 文档
- 项目文档在 `docs/` 目录
- 设计文档放在 docs 中
- 需求文档放在 docs 中
- 不依赖特定文件名，按内容分类

## 工作流

1. **新功能**: `master` → `dev` → 开发 → 合并回 `master`
2. **日常开发**: 直接在 `dev` 分支
3. **合并到 master**: `git checkout master && git merge dev`

## 验证

改完代码必须运行验证：
- 后端: `cd backend && mvn test && mvn clean install`
