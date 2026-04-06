package com.aicodeassistant.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * 应用状态存储 — 线程安全的不可变状态管理。
 * <p>
 * 状态变更通过 {@link #setState(UnaryOperator)} 原子更新，
 * 变更后通知所有已注册的监听器。
 * <p>
 * 线程安全保证:
 * <ul>
 *     <li>state 字段使用 volatile 保证可见性</li>
 *     <li>setState 使用 synchronized 保证原子更新</li>
 *     <li>listeners 使用 CopyOnWriteArrayList 保证遍历安全</li>
 * </ul>
 *
 * @see <a href="SPEC §3.5.2">状态存储</a>
 */
@Service
public class AppStateStore {

    private static final Logger log = LoggerFactory.getLogger(AppStateStore.class);

    private volatile AppState state;
    private final List<Consumer<AppState>> listeners = new CopyOnWriteArrayList<>();
    private final List<StateChangeListener> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * 状态变更监听器 — 接收新旧状态便于对比。
     */
    @FunctionalInterface
    public interface StateChangeListener {
        void onStateChange(AppState oldState, AppState newState);
    }

    public AppStateStore() {
        this.state = AppState.defaultState();
        log.info("AppStateStore initialized with default state");
    }

    /**
     * 获取当前状态快照（不可变，线程安全读取）。
     */
    public AppState getState() {
        return state;
    }

    /**
     * 原子更新状态 — 通过函数式更新器生成新状态。
     * synchronized 保证同一时刻只有一个写入者。
     *
     * @param updater 状态更新函数，接收旧状态返回新状态
     */
    public synchronized void setState(UnaryOperator<AppState> updater) {
        AppState oldState = this.state;
        this.state = updater.apply(oldState);

        // 通知变更监听器 (old/new 对比)
        for (StateChangeListener listener : changeListeners) {
            try {
                listener.onStateChange(oldState, this.state);
            } catch (Exception e) {
                log.error("StateChangeListener error: {}", e.getMessage());
            }
        }

        // 通知普通监听器
        notifyListeners(this.state);
    }

    /**
     * 订阅状态变更（仅接收新状态）。
     */
    public void subscribe(Consumer<AppState> listener) {
        listeners.add(listener);
    }

    /**
     * 取消订阅。
     */
    public void unsubscribe(Consumer<AppState> listener) {
        listeners.remove(listener);
    }

    /**
     * 订阅状态变更（接收新旧状态，用于副作用对比）。
     */
    public void subscribeChange(StateChangeListener listener) {
        changeListeners.add(listener);
    }

    /**
     * 取消变更订阅。
     */
    public void unsubscribeChange(StateChangeListener listener) {
        changeListeners.remove(listener);
    }

    private void notifyListeners(AppState newState) {
        for (Consumer<AppState> listener : listeners) {
            try {
                listener.accept(newState);
            } catch (Exception e) {
                log.error("AppState listener error: {}", e.getMessage());
            }
        }
    }
}
