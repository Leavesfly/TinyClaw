package io.leavesfly.tinyclaw.bus;

/**
 * 当消息总线已关闭时，尝试消费消息会抛出此异常。
 * 调用方应捕获此异常并退出消费循环。
 */
public class BusClosedException extends RuntimeException {

    public BusClosedException() {
        super("MessageBus is closed");
    }

    public BusClosedException(String message) {
        super(message);
    }
}
