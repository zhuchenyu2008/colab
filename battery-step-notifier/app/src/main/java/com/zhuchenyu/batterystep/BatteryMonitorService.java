package com.zhuchenyu.batterystep;

/**
 * Compatibility name kept so existing UI/boot code can continue calling
 * BatteryMonitorService.* while the implementation lives in
 * ReliableBatteryMonitorService.
 */
public class BatteryMonitorService extends ReliableBatteryMonitorService {
}
