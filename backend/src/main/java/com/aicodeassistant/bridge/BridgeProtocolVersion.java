package com.aicodeassistant.bridge;

/**
 * 桥接传输协议版本。
 *
 * @see <a href="SPEC §4.5.2">双协议支持</a>
 */
public enum BridgeProtocolVersion {
    /** WebSocket(读) + HTTP(写) 混合传输 */
    V1_HYBRID,
    /** SSE(读) + HTTP(写) 云端远程传输 */
    V2_SSE_CCR
}
