package com.share.share.service;

import com.share.share.constant.ShareConstants;
import com.share.share.entity.ShareApi;
import com.share.share.entity.ShareCallStat;
import com.share.share.entity.ShareCredential;
import com.share.share.repository.ShareApiRepository;
import com.share.share.repository.ShareCallStatRepository;
import com.share.share.support.ShareUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 共享服务调用计量与统计（FR-07）。
 * <p>每次调用落一条 {@code shr_call_stat}；统计接口按 apiId/时间范围聚合
 * 调用量、成功率、平均耗时与 P95 耗时，并给出按日趋势与按服务分布。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareCallStatService {

    private final ShareCallStatRepository statRepository;
    private final ShareApiRepository apiRepository;

    /** 记录一次调用（开放网关 / 在线调试）。 */
    @Transactional
    public void record(ShareApi api, ShareCredential credential, String channel, long costMs,
                       String status, int rowCount, String ip, String errorMsg) {
        ShareCallStat stat = new ShareCallStat();
        stat.setApiId(api == null ? null : api.getId());
        stat.setApiCode(api == null ? null : api.getApiCode());
        stat.setApiName(api == null ? null : api.getApiName());
        if (credential != null) {
            stat.setCredentialId(credential.getId());
            stat.setAppKey(credential.getAppKey());
            stat.setConsumerId(credential.getConsumerId());
        } else {
            stat.setAppKey(ShareConstants.CHANNEL_DEBUG);
        }
        stat.setChannel(channel);
        stat.setCostMs(costMs);
        stat.setStatus(status);
        stat.setRowCount(rowCount);
        stat.setCallTime(LocalDateTime.now());
        stat.setIp(ip);
        stat.setErrorMsg(ShareUtils.truncate(errorMsg, 500));
        statRepository.save(stat);
    }

    /** 调用统计聚合。 */
    public Map<String, Object> stats(Long apiId, String from, String to) {
        LocalDateTime end = ShareUtils.parseDateTime(to);
        LocalDateTime start = ShareUtils.parseDateTime(from);
        if (end == null) {
            end = LocalDateTime.now();
        }
        if (start == null) {
            start = end.minusDays(7);
        }
        List<ShareCallStat> rows = apiId != null
                ? statRepository.findByApiIdAndCallTimeBetween(apiId, start, end)
                : statRepository.findByCallTimeBetween(start, end);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("apiId", apiId);
        data.put("from", start.toString());
        data.put("to", end.toString());

        long total = rows.size();
        long success = rows.stream().filter(r -> ShareConstants.STAT_SUCCESS.equals(r.getStatus())).count();
        long fail = total - success;
        List<Long> costs = rows.stream().map(r -> r.getCostMs() == null ? 0L : r.getCostMs()).toList();
        long sumRows = rows.stream().mapToLong(r -> r.getRowCount() == null ? 0 : r.getRowCount()).sum();

        data.put("totalCalls", total);
        data.put("successCalls", success);
        data.put("failCalls", fail);
        data.put("successRate", rate(success, total));
        data.put("avgCostMs", avg(costs));
        data.put("p95CostMs", p95(costs));
        data.put("maxCostMs", costs.stream().mapToLong(Long::longValue).max().orElse(0L));
        data.put("totalRows", sumRows);

        // 按服务分布
        Map<Long, List<ShareCallStat>> byApiId = new LinkedHashMap<>();
        for (ShareCallStat row : rows) {
            byApiId.computeIfAbsent(row.getApiId(), k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> byApi = new ArrayList<>();
        byApiId.forEach((id, list) -> {
            if (id == null) {
                return;
            }
            long ok = list.stream().filter(r -> ShareConstants.STAT_SUCCESS.equals(r.getStatus())).count();
            ShareApi api = apiRepository.findById(id).orElse(null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("apiId", id);
            item.put("apiCode", list.get(0).getApiCode());
            item.put("apiName", list.get(0).getApiName());
            item.put("catalogName", api == null ? null : api.getCatalogName());
            item.put("calls", list.size());
            item.put("successCalls", ok);
            item.put("successRate", rate(ok, list.size()));
            item.put("avgCostMs", avg(list.stream().map(r -> r.getCostMs() == null ? 0L : r.getCostMs()).toList()));
            item.put("p95CostMs", p95(list.stream().map(r -> r.getCostMs() == null ? 0L : r.getCostMs()).toList()));
            byApi.add(item);
        });
        byApi.sort(Comparator.comparingLong(m -> -((Number) m.get("calls")).longValue()));
        data.put("byApi", byApi);

        // 按日趋势
        Map<LocalDate, List<ShareCallStat>> byDay = new TreeMap<>();
        for (ShareCallStat row : rows) {
            LocalDate day = row.getCallTime() == null ? LocalDate.now() : row.getCallTime().toLocalDate();
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> byDayList = new ArrayList<>();
        byDay.forEach((day, list) -> {
            long ok = list.stream().filter(r -> ShareConstants.STAT_SUCCESS.equals(r.getStatus())).count();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", day.toString());
            item.put("calls", list.size());
            item.put("successCalls", ok);
            item.put("failCalls", list.size() - ok);
            item.put("successRate", rate(ok, list.size()));
            item.put("avgCostMs", avg(list.stream().map(r -> r.getCostMs() == null ? 0L : r.getCostMs()).toList()));
            item.put("rows", list.stream().mapToLong(r -> r.getRowCount() == null ? 0 : r.getRowCount()).sum());
            byDayList.add(item);
        });
        data.put("byDay", byDayList);

        // 按渠道分布
        long openCalls = rows.stream().filter(r -> ShareConstants.CHANNEL_OPEN.equals(r.getChannel())).count();
        data.put("byChannel", List.of(
                Map.of("channel", ShareConstants.CHANNEL_OPEN, "calls", openCalls),
                Map.of("channel", ShareConstants.CHANNEL_DEBUG, "calls", total - openCalls)));
        return data;
    }

    private double rate(long part, long total) {
        if (total <= 0) {
            return 0d;
        }
        return BigDecimal.valueOf(part * 100.0 / total).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private double avg(List<Long> costs) {
        if (costs == null || costs.isEmpty()) {
            return 0d;
        }
        long sum = costs.stream().mapToLong(Long::longValue).sum();
        return BigDecimal.valueOf((double) sum / costs.size()).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** P95：升序后取第 ceil(0.95*n) 个值。 */
    private long p95(List<Long> costs) {
        if (costs == null || costs.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(costs);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }
}
