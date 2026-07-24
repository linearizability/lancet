# Lancet（柳叶刀）设计文档

> 一个基于 Java Agent 的进程内方法调用网关，让本地测试时的远程方法调用像本地调用一样简单。

---

## 1. 项目概述

### 1.1 名称与寓意

**Lancet**（柳叶刀）

- **精准**：像手术刀一样直达目标方法，不绕弯路
- **微创**：对目标应用零代码侵入，仅通过 `-javaagent` 参数挂载
- **专业**：面向开发和测试人员的调试利器

### 1.2 背景与痛点

在日常开发和本地测试过程中，经常遇到以下场景：

| 场景 | 传统做法 | 痛点 |
|------|---------|------|
| 测试某个业务方法 | 写单元测试类 | 需要手动 Mock 依赖、构造上下文，成本高 |
| 走完整业务流程触发方法 | 调接口 → 鉴权 → 造数据 → 走流程 | 链路长、准备数据繁琐、鉴权复杂 |
| 跨项目调用方法 | 引入依赖或暴露 HTTP 接口 | 需要目标项目配合改造，破坏隔离性 |
| 多个框架的项目 | 各自写测试工具 | 重复造轮子，无法复用 |

**Lancet 想要解决的核心问题**：
> 已知一个运行中的 Java 进程的某个类和方法，直接给它参数，让它执行——就像在本机调用一样。

### 1.3 适用范围

- **本地开发调试**：绕过接口层、鉴权层、业务流程层，直达业务方法
- **集成测试**：模拟上游系统向目标应用推送数据（如订单、通知）
- **跨项目协作**：A 项目想触发 B 项目的某个方法，但 B 不愿/不能暴露接口
- **框架无关**：Guice、Spring Boot、纯 Java 项目均可支持

---

## 2. 设计目标

### 2.1 核心目标

| 目标 | 说明 |
|------|------|
| **零代码侵入** | 目标应用无需改一行代码，仅需启动参数加 `-javaagent` |
| **框架无关** | 支持 Guice、Spring、裸 Java 等多种依赖管理框架 |
| **JDK 全兼容** | Agent 用 JDK 8 编译，可 attach 到 JDK 8/11/17/21 |
| **像本地调用** | B 项目通过动态代理调用，语法与本地方法完全一致 |
| **双模式调用** | 既支持程序化的代理调用，也支持 curl/Postman 直接调用 |

### 2.2 非目标

- **不用于生产环境**：仅面向本地开发和测试，不处理安全认证、限流、熔断
- **不替代正规 RPC**：不是 Dubbo/gRPC 的竞品，而是调试辅助工具
- **不保证事务一致性**：反射调用绕过了部分 AOP 拦截，复杂事务场景慎用

---

## 3. 架构设计

### 3.1 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│  调用方（B 项目 / 测试脚本 / curl）                            │
│  ┌─────────────────┐    ┌─────────────────────────────┐    │
│  │ 动态代理模式     │    │ HTTP 直接调用模式            │    │
│  │ LocalOrderApi   │    │ POST /invoke                │    │
│  │   api.receive() │    │ {className, methodName,...} │    │
│  └────────┬────────┘    └─────────────┬───────────────┘    │
│           │                           │                    │
│           └───────────┬───────────────┘                    │
│                       ▼                                    │
│              ┌─────────────────┐                           │
│              │  HTTP Client    │                           │
│              │ (URLConnection) │                           │
│              └────────┬────────┘                           │
└───────────────────────┼────────────────────────────────────┘
                        │ HTTP / JSON
                        ▼
┌─────────────────────────────────────────────────────────────┐
│  目标应用 JVM（A 项目）                                       │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Lancet Agent（-javaagent 挂载）                      │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌───────────┐ │   │
│  │  │ HTTP Server  │→ │ InvokeHandler│→ │ Framework │ │   │
│  │  │ (内置 9999)  │  │ (反射调用)    │  │ Adapter   │ │   │
│  │  └──────────────┘  └──────────────┘  └─────┬─────┘ │   │
│  └────────────────────────────────────────────┼────────┘   │
│                                               │             │
│  ┌────────────────────────────────────────────┼────────┐   │
│  │  目标应用业务代码                             │        │   │
│  │  ┌─────────────────┐   ┌──────────────────┐ │        │   │
│  │  │ Guice Injector  │   │ Spring Context   │◄┘        │   │
│  │  │  Topos.get()    │   │  getBean()       │          │   │
│  │  └────────┬────────┘   └────────┬─────────┘          │   │
│  │           │                     │                    │   │
│  │           └──────────┬──────────┘                    │   │
│  │                      ▼                               │   │
│  │           ┌─────────────────────┐                    │   │
│  │           │ LocalOrderApiImpl   │                    │   │
│  │           │   receive(Order)  │◄── 真正执行        │   │
│  │           └─────────────────────┘                    │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 核心组件

| 组件 | 职责 | 所在模块 |
|------|------|---------|
| **Agent Launcher** | `premain` 入口，挂载到目标 JVM，启动生命周期 | `lancet-agent` |
| **HTTP Server** | 内置 HTTP 服务，监听调用请求 | `lancet-agent` |
| **Invoke Handler** | 解析请求，反射定位方法，执行调用 | `lancet-agent` |
| **Framework Adapter** | 从 Guice/Spring/裸 Java 中获取 Bean 实例 | `lancet-agent` |
| **Proxy Factory** | 为接口生成动态代理，隐藏 HTTP 通信细节 | `lancet-client` |
| **HTTP Client** | 发送 JSON 请求到 Agent，接收响应 | `lancet-client` |

---

## 4. 模块划分

```
lancet/
├── lancet-agent/          # Agent 核心（打 fat jar，用于 -javaagent）
│   ├── pom.xml
│   └── src/main/java/com/lancet/agent/
│       ├── LancetAgent.java              # premain 入口
│       ├── AgentHttpServer.java          # 内置 HTTP 服务
│       ├── InvokeHandler.java            # 调用处理核心
│       ├── adapter/
│       │   ├── FrameworkAdapter.java     # 适配器接口
│       │   ├── GuiceAdapter.java         # Guice/Topos 适配
│       │   ├── SpringAdapter.java        # Spring Boot 适配
│       │   └── PlainAdapter.java         # 裸 Java 适配（反射 newInstance）
│       └── dto/
│           ├── InvocationRequest.java    # 调用请求 DTO
│           └── InvocationResult.java     # 调用响应 DTO
│
├── lancet-client/         # 客户端 SDK（B 项目引入）
│   ├── pom.xml
│   └── src/main/java/com/lancet/client/
│       ├── LancetProxyFactory.java       # 动态代理工厂
│       └── HttpInvokeClient.java         # HTTP 通信客户端
│
└── pom.xml                # 父 POM，统一定义 JDK 8 编译、Gson 版本
```

### 4.1 模块职责边界

- **`lancet-agent`**：纯服务端，无业务侵入性。打包为 fat jar（内含 Gson），通过 `-javaagent:lancet-agent.jar` 挂载。
- **`lancet-client`**：纯客户端，提供两种调用方式：
  1. **动态代理模式**：`LancetProxyFactory.create(LocalOrderApi.class)` → 像本地调用
  2. **裸 HTTP 模式**：`HttpInvokeClient.invoke(request)` → 直接发 JSON

---

## 5. 关键技术方案

### 5.1 Java Agent 机制

```java
public class LancetAgent {
    public static void premain(String agentArgs, Instrumentation inst) {
        // 1. 解析参数（端口、框架类型）
        AgentConfig config = AgentConfig.parse(agentArgs);
        
        // 2. 启动守护线程，等待目标应用初始化完成
        new Thread(() -> {
            Object frameworkContext = waitForFrameworkReady(config.getFrameworkType());
            
            // 3. 启动 HTTP 服务
            new AgentHttpServer(config.getPort(), frameworkContext).start();
        }, "lancet-init").start();
    }
}
```

**启动参数**：
```bash
-javaagent:/path/to/lancet-agent.jar=port=9999,framework=guice
```

### 5.2 进程内 HTTP 服务

**选型**：`com.sun.net.httpserver.HttpServer`

| 方案 | JDK 8 | JDK 11+ | 依赖 | 结论 |
|------|-------|---------|------|------|
| `HttpServer` | ✅ | ✅（module path 需 `--add-modules jdk.httpserver`） | 零依赖 | **选定** |
| Vert.x / Netty | ✅ | ✅ | 额外 jar | jar 体积大 |
| 自研 Socket | ✅ | ✅ | 零依赖 | 需手写 HTTP 解析，成本高 |

**注意**：`HttpServer` 在生产环境不推荐，但在本地测试场景完全够用。

### 5.3 框架适配层

**设计原则**：Agent 通过反射探测目标 JVM 中存在的框架，按需适配。

```java
public interface FrameworkAdapter {
    /**
     * 根据类名获取实例
     * @param className 接口/类全限定名
     * @return 实例对象
     */
    Object getInstance(String className) throws Exception;
    
    /**
     * 检查当前 JVM 是否支持该框架
     */
    boolean isAvailable();
}
```

**Guice/Topos 适配实现**：
```java
public class GuiceAdapter implements FrameworkAdapter {
    public Object getInstance(String className) {
        Class<?> topos = Class.forName("topos.store.framework.Topos");
        return topos.getMethod("get", Class.class)
                    .invoke(null, Class.forName(className));
    }
    
    public boolean isAvailable() {
        return exists("topos.store.framework.Topos") 
            || exists("com.google.inject.Injector");
    }
}
```

**Spring Boot 适配实现**：
```java
public class SpringAdapter implements FrameworkAdapter {
    public Object getInstance(String className) {
        // 从 Spring 的 ApplicationContext 中获取
        Object context = findSpringContext(); // 通过反射找 context
        return context.getClass()
                      .getMethod("getBean", Class.class)
                      .invoke(context, Class.forName(className));
    }
}
```

**裸 Java 适配实现**：
```java
public class PlainAdapter implements FrameworkAdapter {
    public Object getInstance(String className) {
        Class<?> clazz = Class.forName(className);
        // 尝试获取单例（静态 INSTANCE 字段）
        try {
            return clazz.getField("INSTANCE").get(null);
        } catch (NoSuchFieldException e) {
            //  fallback：反射 newInstance
            return clazz.newInstance();
        }
    }
}
```

**自动探测顺序**：
1. 若用户显式指定了 `framework=xxx`，使用指定适配器
2. 否则按顺序探测：`SpringAdapter` → `GuiceAdapter` → `PlainAdapter`

### 5.4 JSON 序列化策略

**选型**：Gson 2.8.x

| 考量 | 说明 |
|------|------|
| 兼容性 | Gson 2.8.x 支持 JDK 6+ |
| 体积 | ~200KB，打包进 fat jar 可接受 |
| 复杂度 | `Order` 包含 `LocalDateTime`、`List<T>` 等，Gson 可处理 |
| 冲突风险 | Gson 无静态状态，与目标应用共存风险低 |

**序列化边界**：
- **入参**：B 端将 `Order` Gson 序列化 → Agent 端用目标 JVM 的 `Class.forName` 加载类后 Gson 反序列化
- **返回值**：Agent 端 Gson 序列化 → B 端 Gson 反序列化
- **void 方法**：返回 `{"success": true, "data": null}`

### 5.5 调用协议

**端点**：`POST /invoke`

**请求体**：
```json
{
  "className": "topos.store.domain.wsonrpc.LocalOrderApi",
  "methodName": "receive",
  "paramTypes": [
    "topos.store.domain.model.Order"
  ],
  "paramsJson": [
    "{\"orderId\":123,\"orderStatus\":30,...}"
  ]
}
```

**响应体**：
```json
{
  "success": true,
  "data": null,
  "errorMsg": null,
  "costMillis": 15
}
```

**错误响应**：
```json
{
  "success": false,
  "data": null,
  "errorMsg": "java.lang.IllegalArgumentException: orderId is null",
  "costMillis": 5
}
```

### 5.6 动态代理（B 项目体验）

```java
public class LancetProxyFactory {
    private final String agentUrl;
    private final Gson gson = new Gson();
    
    public <T> T create(Class<T> interfaceClass) {
        return (T) Proxy.newProxyInstance(
            interfaceClass.getClassLoader(),
            new Class<?>[]{interfaceClass},
            (proxy, method, args) -> {
                // 1. 构造请求
                InvocationRequest req = new InvocationRequest();
                req.setClassName(interfaceClass.getName());
                req.setMethodName(method.getName());
                req.setParamTypes(Arrays.stream(method.getParameterTypes())
                    .map(Class::getName).toArray(String[]::new));
                req.setParamsJson(Arrays.stream(args)
                    .map(gson::toJson).toArray(String[]::new));
                
                // 2. 发送 HTTP 请求
                InvocationResult result = HttpInvokeClient.post(agentUrl + "/invoke", req);
                
                // 3. 处理响应
                if (!result.isSuccess()) {
                    throw new RuntimeException(result.getErrorMsg());
                }
                
                // 4. 反序列化返回值
                if (method.getReturnType() == void.class) {
                    return null;
                }
                return gson.fromJson(result.getData(), method.getGenericReturnType());
            }
        );
    }
}
```

**使用体验**：
```java
// B 项目代码
LancetProxyFactory factory = new LancetProxyFactory("http://localhost:9999");
LocalOrderApi api = factory.create(LocalOrderApi.class);

Order order = new Order();
order.setOrderId(123L);
// ... 构造参数

api.receive(order);  // 就像本地调用，实际在 A 进程执行
```

---

## 6. 详细设计

### 6.1 Agent 启动时序

```
目标应用启动
    │
    ▼
JVM 加载 -javaagent:lancet-agent.jar=port=9999,framework=guice
    │
    ▼
LancetAgent.premain(args, inst)
    │
    ├── 解析 agentArgs → AgentConfig
    │
    ├── 启动 daemon 线程 "lancet-init"
    │       │
    │       ▼
    │   waitForFrameworkReady()
    │       │
    │       ├── 循环检测 Class.forName("topos.store.framework.Topos")
    │       ├── 检测 Topos.framework() 是否非空
    │       └── 获取 FrameworkAdapter
    │
    └── daemon 线程继续
            │
            ▼
    AgentHttpServer.start()
            │
            ├── HttpServer.create(new InetSocketAddress(port), 0)
            ├── server.createContext("/invoke", new InvokeHandler(adapter))
            └── server.start()
            │
            ▼
    等待 HTTP 请求...
```

### 6.2 单次调用时序

```
B 项目调用 api.receive(order)
    │
    ▼
Proxy InvocationHandler
    │
    ├── 序列化参数（Gson）
    ├── 构造 InvocationRequest
    └── HTTP POST → localhost:9999/invoke
            │
            ▼
    AgentHttpServer 接收请求
            │
            ▼
    InvokeHandler.handle()
            │
            ├── 解析 JSON → InvocationRequest
            ├── Class.forName(className) 加载接口类
            ├── adapter.getInstance(className) 获取实例
            ├── 根据 methodName + paramTypes 定位 Method
            ├── 将 paramsJson 反序列化为实际参数对象
            ├── method.invoke(instance, args) 执行
            ├── 捕获返回值或异常
            └── 构造 InvocationResult → JSON 响应
            │
            ▼
    HTTP Response → B 项目
            │
            ▼
    Proxy InvocationHandler
            │
            ├── 解析 InvocationResult
            ├── 如有异常则抛出
            └── 反序列化 data 为返回值类型
            │
            ▼
    返回给 B 项目调用方
```

### 6.3 异常处理策略

| 异常阶段 | 处理方式 | 响应 |
|---------|---------|------|
| JSON 解析失败 | 捕获 `JsonSyntaxException` | `success=false, errorMsg=解析错误详情` |
| 类找不到 | 捕获 `ClassNotFoundException` | `success=false, errorMsg=类未找到` |
| 方法找不到 | 捕获 `NoSuchMethodException` | `success=false, errorMsg=方法签名不匹配` |
| 框架未就绪 | 捕获 `IllegalStateException` | `success=false, errorMsg=目标框架未初始化完成` |
| 业务异常 | 捕获 `InvocationTargetException` | `success=false, errorMsg=业务异常堆栈` |
| 正常执行 | 返回结果 | `success=true, data=序列化结果` |

---

## 7. 使用方式

### 7.1 方式一：curl / Postman（最快速）

适合一次性测试、不写入代码的场景：

```bash
curl -X POST http://localhost:9999/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "className": "topos.store.domain.wsonrpc.LocalOrderApi",
    "methodName": "receive",
    "paramTypes": ["topos.store.domain.model.Order"],
    "paramsJson": ["{\"orderId\":123456,\"orderStatus\":30,...}"]
  }'
```

### 7.2 方式二：Java 动态代理（最自然）

B 项目中：

```java
public class TestMain {
    public static void main(String[] args) {
        LancetProxyFactory factory = new LancetProxyFactory("http://localhost:9999");
        
        // 就像本地获取了一个 Bean
        LocalOrderApi api = factory.create(LocalOrderApi.class);
        
        Order order = new Order();
        order.setOrderId(System.currentTimeMillis());
        order.setOrderStatus(30);
        // ... 其他字段
        
        api.receive(order);  // 远程执行！
    }
}
```

### 7.3 方式三：IDEA 启动配置

目标应用（A 项目）的 IDEA Run Configuration 中：

```
VM options:
-javaagent:D:	oolslancet-agent.jar=port=9999,framework=guice
```

启动 A 后，Agent 自动挂载，日志输出：
```
[lancet] Agent mounted, waiting for framework ready...
[lancet] Guice framework detected.
[lancet] HTTP server started on port 9999
```

---

## 8. 兼容性设计

### 8.1 JDK 版本兼容

| Agent 编译版本 | 目标 JVM JDK 8 | 目标 JVM JDK 11 | 目标 JVM JDK 17 | 目标 JVM JDK 21 |
|---------------|---------------|----------------|----------------|----------------|
| JDK 8 (1.8) | ✅ | ✅ | ✅ | ✅ |

**编译配置**：
```xml
<properties>
    <maven.compiler.source>1.8</maven.compiler.source>
    <maven.compiler.target>1.8</maven.compiler.target>
</properties>
```

**JDK 17+ 强封装处理**：
- Agent 仅调用 `public` 方法，避开默认强封装限制
- 如需调用非 public 方法，可通过 `Instrumentation` 在 `premain` 中打开模块（预留扩展点）

### 8.2 框架兼容矩阵

| 框架 | 适配方式 | 状态 |
|------|---------|------|
| Guice / Topos | `Topos.get(Class)` / `Injector.getInstance()` | MVP 支持 |
| Spring Boot | `ApplicationContext.getBean()` | MVP 支持 |
| 裸 Java（静态单例）| 反射获取 `INSTANCE` 字段 | MVP 支持 |
| 裸 Java（需 new） | `Class.newInstance()` | MVP 支持 |
| CDI / Weld | `BeanManager.getReference()` | 后续扩展 |
| OSGi | `BundleContext.getService()` | 后续扩展 |

### 8.3 类加载器处理

Agent 与目标应用共享 `AppClassLoader` 的类可见性（双亲委派模型）：

```
Bootstrap ClassLoader
      │
      ▼
Platform ClassLoader (JDK 9+)
      │
      ▼
AppClassLoader（加载目标应用和 Agent 的类）
      │
      ├── 目标业务类（LocalOrderApiImpl）
      └── Agent 类（LancetAgent, InvokeHandler）
```

Agent 中使用 `Class.forName(String)` 即可加载目标应用的类，无需关心 ClassLoader。

---

## 9. 安全与限制

### 9.1 安全声明

**Lancet 仅用于本地开发和测试环境，严禁用于生产环境。**

原因：
- Agent 暴露了任意类方法的反射调用能力，无鉴权机制
- 可绕过业务校验、鉴权、日志等 AOP 拦截
- 可能被利用执行危险操作（如删除数据、关机等）

### 9.2 已知限制

| 限制 | 说明 |
|------|------|
| **ThreadLocal 缺失** | 反射调用绕过了 Filter/Interceptor，可能缺失请求上下文（如当前登录用户） |
| **AOP 失效** | 如果目标方法依赖 Spring AOP/Guice AOP 的拦截逻辑，直接反射调用可能不触发 |
| **事务边界** | `@Transactional` 等声明式事务基于代理实现，直接反射调用原始实例可能不触发事务 |
| **返回值序列化** | 复杂返回值（如循环引用、流对象）可能 JSON 序列化失败 |
| **void 方法** | 支持，但无法确认副作用是否成功 |

### 9.3 规避建议

- **优先调用 Service 层/Domain 层的无状态方法**
- **避免直接调用 Controller/Endpoint 层方法**（缺失 HTTP 上下文）
- **参数构造完整**：反射调用不会走参数校验注解（如 `@Valid`、`@NotNull`）
- **关注副作用**：如方法内有数据库写入、消息发送等，确保测试环境隔离

---

## 10. 扩展规划

### 10.1 近期（MVP 后）

| 功能 | 说明 |
|------|------|
| **批量调用** | 一次 HTTP 请求执行多个方法调用 |
| **调用记录** | Agent 端记录最近 100 次调用的入参、返回值、耗时 |
| **健康检查** | `GET /health` 端点，返回 Agent 状态和框架类型 |
| **IDEA 插件** | 右键目标方法 → "Lancet Invoke"，自动生成参数模板 |

### 10.2 中期

| 功能 | 说明 |
|------|------|
| **参数模板** | Agent 扫描目标类的方法签名，返回参数 JSON Schema，辅助构造入参 |
| **Mock 拦截** | 指定某些依赖返回 Mock 值，隔离外部服务 |
| **性能剖析** | 记录方法内部各阶段耗时（预留 JFR 集成） |

### 10.3 远期

| 功能 | 说明 |
|------|------|
| **远程 attach** | 不仅支持 `-javaagent`（启动时），还支持 `VirtualMachine.attach()`（运行时 attach） |
| **多语言 Client** | Python/Go 客户端，通过 HTTP 调用 Java 方法 |
| **脚本化** | 支持 Groovy/JS 脚本在目标 JVM 中执行 |

---

## 11. 附录

### 11.1 DTO 定义

```java
// InvocationRequest.java
public class InvocationRequest {
    private String className;       // 目标类全限定名
    private String methodName;      // 方法名
    private String[] paramTypes;    // 参数类型全限定名数组
    private String[] paramsJson;    // 参数 JSON 字符串数组
    
    // getter / setter
}

// InvocationResult.java
public class InvocationResult {
    private boolean success;        // 是否成功
    private String data;            // 返回值 JSON（null 时为 "null"）
    private String errorMsg;        // 错误信息
    private long costMillis;        // 执行耗时
    
    // getter / setter
}
```

### 11.2 Maven 打包配置

```xml
<!-- lancet-agent/pom.xml -->
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-shade-plugin</artifactId>
      <version>3.4.1</version>
      <executions>
        <execution>
          <phase>package</phase>
          <goals>
            <goal>shade</goal>
          </goals>
          <configuration>
            <createDependencyReducedPom>false</createDependencyReducedPom>
            <transformers>
              <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                <manifestEntries>
                  <Premain-Class>com.linearizability.lancet.agent.LancetAgent</Premain-Class>
                  <Can-Redefine-Classes>true</Can-Redefine-Classes>
                  <Can-Retransform-Classes>true</Can-Retransform-Classes>
                </manifestEntries>
              </transformer>
            </transformers>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

### 11.3 示例：完整调用链路

**目标应用（A）启动**：
```bash
java -javaagent:lancet-agent.jar=port=9999,framework=guice -jar app-a.jar
```

**调用方（B）代码**：
```java
LancetProxyFactory factory = new LancetProxyFactory("http://localhost:9999");
LocalOrderApi api = factory.create(LocalOrderApi.class);

Order order = Topos.fromJson(orderJson, Order.class);
api.receive(order);
```

**等效 curl**：
```bash
curl -X POST http://localhost:9999/invoke \
  -H "Content-Type: application/json" \
  -d @order-request.json
```

---

## 12. 命名约定

| 术语 | 含义 |
|------|------|
| **目标应用** | 被挂载 Agent 的 Java 进程（A 项目） |
| **调用方** | 发起方法调用的测试程序/项目（B 项目） |
| **Agent** | `lancet-agent.jar`，通过 `-javaagent` 挂载到目标 JVM |
| **Client** | `lancet-client.jar`，被调用方引入的 SDK |
| **FrameworkAdapter** | 框架适配器，负责从 Guice/Spring 中获取实例 |
| **InvokeHandler** | Agent 端的 HTTP 请求处理器 |

---
