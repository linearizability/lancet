# Lancet（柳叶刀）

> 基于 Java Agent 的进程内方法调用网关，让本地测试时的远程方法调用像本地调用一样简单。

---

## 1. 项目简介

Lancet 是一个面向开发和测试人员的调试工具，通过 `-javaagent` 挂载到目标 JVM，暴露一个 HTTP 端点，允许你像调用本地方法一样调用目标进程内的任意 Bean 方法。

**核心能力**：

- **零代码侵入**：目标应用无需改一行代码，仅需启动参数加 `-javaagent`
- **框架无关**：支持 Guice、Spring Boot、裸 Java 等多种依赖管理框架
- **像本地调用**：通过动态代理，调用语法与本地方法完全一致
- **双模式调用**：既支持程序化的代理调用，也支持 curl/Postman 直接调用

---

## 2. 项目结构

```
lancet/
├── pom.xml                          # 父 POM（JDK 1.8，Gson 2.8.9）
├── lancet-agent/                    # Agent 核心模块（打 fat jar）
│   ├── pom.xml                      # maven-shade-plugin 打包配置
│   └── src/main/java/com/lancet/agent/
│       ├── LancetAgent.java         # premain 入口
│       ├── AgentConfig.java         # 配置解析（port, framework）
│       ├── AgentHttpServer.java     # 内置 HTTP 服务（com.sun.net.httpserver）
│       ├── InvokeHandler.java       # 调用处理核心（反射 + Gson 反序列化）
│       ├── adapter/
│       │   ├── FrameworkAdapter.java
│       │   ├── GuiceAdapter.java    # Guice 适配
│       │   ├── SpringAdapter.java   # Spring Boot 适配
│       │   └── PlainAdapter.java    # 裸 Java 适配（INSTANCE / newInstance）
│       └── dto/
│           ├── InvocationRequest.java
│           └── InvocationResult.java
│
└── lancet-client/                   # 客户端 SDK
    ├── pom.xml
    └── src/main/java/com/lancet/client/
        ├── LancetProxyFactory.java  # JDK 动态代理工厂
        └── HttpInvokeClient.java    # URLConnection HTTP 客户端
```

---

## 3. 快速开始

### 3.1 编译打包

```bash
# 在项目根目录执行
mvn clean package
```

打包后生成：

- `lancet-agent/target/lancet-agent-1.0-SNAPSHOT.jar` —— **fat jar**（内含 Gson），用于 `-javaagent` 挂载
- `lancet-client/target/lancet-client-1.0-SNAPSHOT.jar` —— 客户端 SDK

### 3.2 挂载 Agent 到目标应用

在目标应用（A 项目）的启动参数中添加：

```bash
-javaagent:/absolute/path/to/lancet-agent-1.0-SNAPSHOT.jar=port=9999,framework=guice
```

**参数说明**：

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `port` | Agent HTTP 服务监听端口 | `9999` |
| `framework` | 指定框架类型（`guice`/`spring`/`plain`），不指定则自动探测 | 自动探测 |

**IDEA 配置示例**：

Run Configuration → VM options：
```
-javaagent:D:\\Code\\Running\\lancet-agent-1.0-SNAPSHOT.jar=port=9999,framework=guice
```

> **注意**：Windows 路径中的反斜杠必须双写（`\\`），或使用正斜杠（`/`）。

### 3.3 确认 Agent 启动成功

目标应用启动后，控制台应出现以下日志：

```
[lancet] Agent mounted, waiting for framework ready...
[lancet] Framework detected: GuiceAdapter
[lancet] HTTP server started on port 9999
```

---

## 4. 使用方式

### 方式一：curl / Postman（最快速）

```bash
curl -X POST http://localhost:9999/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "className": "topos.store.domain.wsonrpc.LocalOrderApi",
    "methodName": "receive",
    "paramTypes": ["topos.store.domain.model.Order"],
    "paramsJson": ["{\"orderId\":123,\"orderStatus\":30}"]
  }'
```

**请求体字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `className` | String | 目标类/接口全限定名 |
| `methodName` | String | 方法名 |
| `paramTypes` | String[] | 参数类型全限定名数组 |
| `paramsJson` | String[] | 参数 JSON 字符串数组（注意是字符串数组，不是对象数组） |

**响应示例**：

```json
{
  "success": true,
  "data": null,
  "errorMsg": null,
  "costMillis": 15
}
```

### 方式二：Java 动态代理（最自然）

在调用方（B 项目）引入 `lancet-client`：

```java
LancetProxyFactory factory = new LancetProxyFactory("http://localhost:9999");
LocalOrderApi api = factory.create(LocalOrderApi.class);

Order order = new Order();
order.setOrderId(123L);
order.setOrderStatus(30);

api.receive(order);  // 就像本地调用，实际在 A 进程执行
```

---

## 5. 框架适配

Agent 启动时会自动探测目标 JVM 中的框架，探测顺序：**Spring → Guice → Plain**。

| 框架 | 探测方式 | 说明 |
|------|---------|------|
| Spring Boot | 查找 `ApplicationContext`，通过 `getBean(Class)` 获取实例 | 支持 |
| Guice / Topos | 通过 `Topos.get(Class)` 或 Guice `Injector` 获取实例 | 支持 |
| 裸 Java | 先查找静态 `INSTANCE` 字段，不存在则 `newInstance()` | 支持 |

也可以通过 `framework=xxx` 显式指定，跳过自动探测。

---

## 6. 日期时间与特殊类型支持

Agent 端的 Gson 已内置以下 Java 8 时间类型的反序列化支持：

| 类型 | 支持格式 |
|------|---------|
| `LocalDateTime` | `2025-07-17T10:42:45`、`2025-07-17 10:42:45`、`2025-07-17 10:42:45.000` |
| `LocalDate` | `2025-07-17`、`2025/07/17` |
| `java.util.Date` | 同上 |
| `java.nio.file.Path` | 按路径字符串传输，Agent 端用 `Paths.get()` 重建 |

如果目标应用中的类使用了其他特殊类型（如自定义枚举、BigDecimal 特殊格式等），导致 Gson 反序列化失败，请修改 `GsonFactory.create()` 添加对应的 `TypeAdapter`。

---

## 7. 注意事项

- **仅限本地开发和测试环境使用**，严禁用于生产环境
- Agent 暴露了任意类方法的反射调用能力，无鉴权机制
- 反射调用绕过了 Filter/Interceptor，可能缺失请求上下文（如当前登录用户、ThreadLocal）
- 如果目标方法依赖 Spring AOP/Guice AOP 的拦截逻辑，直接反射调用可能不触发
- `@Transactional` 等声明式事务基于代理实现，直接反射调用原始实例可能不触发事务

---

## 8. 技术栈

- JDK 1.8+
- Maven 3.x
- Gson 2.8.9
- `com.sun.net.httpserver.HttpServer`（JDK 内置，零额外依赖）

---