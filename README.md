# 基于 Spring Boot + LangChain4j 的智能问答系统

一个可运行的 AI 问答演示项目（医疗场景），支持：
- 多会话管理
- RAG 检索增强
- 流式输出
- 本地长期记忆（写入/召回/蒸馏）
- 可视化后台管理

---

## 🚀 快速启动

### 方式 1（推荐）
在项目根目录双击：
- `start.bat` 启动
- `stop.bat` 停止

启动后访问：
- 聊天页：`http://localhost:8080/index.html`
- 后台页：`http://localhost:8080/admin.html`

### 方式 2（命令行）
```bash
.\mvnw.cmd spring-boot:run
```

---

## ⚙️ 环境与配置

- Java 17+
- Maven Wrapper（已内置）
- DeepSeek/OpenAI 兼容 API

建议设置环境变量：
- `API_KEY`

---

## 📁 当前目录结构（已整理）

```text
consultant/
├─ src/                     # 源码
├─ data/                    # 本地记忆数据（运行时生成）
├─ docs/
│  ├─ assets/               # 界面截图
│  └─ reports/              # 报告与演示PPT
├─ scripts/
│  ├─ start-all.bat         # 实际启动脚本
│  └─ stop-all.bat          # 实际停止脚本
├─ start.bat                # 一键启动入口
├─ stop.bat                 # 一键停止入口
├─ pom.xml
├─ mvnw
└─ mvnw.cmd
```

---

## ✅ 已实现能力

- `GET /sessions` / `POST /sessions`
- `POST /chat`
- `POST /chat/stream`
- `GET /admin/api/overview`
- `GET /admin/api/memory/sessions`
- `GET /admin/api/memory/{sessionId}`
- `GET /admin/api/memory/{sessionId}/stats`
- `GET /admin/api/memory/{sessionId}/recall`
- `POST /admin/api/memory/{sessionId}/distill`
- `POST /admin/api/database/reset`

---

## 🧪 构建

```bash
.\mvnw.cmd -DskipTests package
```

---

## 说明

本项目用于学习与演示，不替代专业医疗诊疗建议。
