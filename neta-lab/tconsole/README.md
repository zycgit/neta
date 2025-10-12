# Telnet Console 框架

&emsp;&emsp;提供一个 Telnet 环境支持，给予没有界面类的应用一个可以通过命令行进行交互的工具。

----------
## 特性
01. 支持监听本地端口提供 Telnet 交互的界面。
02. 支持基于标准输入输出构建交互控制台的能力。
03. 利用 tConsole 可以轻松构建命令工具包。

## 样例

实现一个控制台命令。
```java
public class HelloWordExecutor implements TelExecutor {
    /** 命令的帮助信息，在 help <command> 时候输出这个信息 */
    public String helpInfo() {
        return "hello help.";  
    }
    /** 执行命令体 */
    public String doCommand(TelCommand telCommand) throws Throwable {
        return "you say ->" + telCommand.getCommandName();
    }
}
```

## Server 模式
利用 tConsole 构建 telnet 服务端，并通过系统命令行来交互。

```java
public static void main(String[] args) {
    SocketTelService server = new SocketTelService();
    server.addCommand("hello", new HelloWordExecutor());
    server.start(2180);
    ...
}
```

输入 `telnet 127.0.0.1 2180` 之后
```text
>telnet 127.0.0.1 2180
Trying 127.0.0.1...
Connected to 127.0.0.1.
Escape character is '^]'.
--------------------------------------------

Welcome to tConsole!

     login : Tue Jan 07 14:26:29 CST 2020 now. form /127.0.0.1:60023
    workAt : /127.0.0.1:2180
Tips: You can enter a 'help' or 'help -a' for more information.
use the 'exit' or 'quit' out of the console.
--------------------------------------------
tConsole>
```

## Client 模式
使用 tConsole 的 Client 工具来连接 Telnet 服务端。

```java
public static void main(String[] args) {
    try (TelClient client = new TelClient()) {
        client.connectTo(new InetSocketAddress("127.0.0.1", 8082));

        String result = client.sendCommand("get abc");
        ...
    }
}
```

## Host 模式

充当命令工具包。

```java
public static void main(String[] args) {
    HostTelService service = new HostTelService();
    service.addCommand("hello", new HelloWordExecutor());

    Reader input  = new InputStreamReader(System.in);
    Writer output = new PrintWriter(System.out);
    telService.startAt(false, input, output);
}
```

执行 main 方法后在控制台会看到如下输出，可以在控制台上注入命令。

```text
--------------------------------------------

Welcome to tConsole!

     login : Sun Oct 12 22:27:42 CST 2025 now. form Session
Tips: You can enter a 'help' or 'help -a' for more information.
use the 'exit' or 'quit' out of the console.
--------------------------------------------
tConsole>
```