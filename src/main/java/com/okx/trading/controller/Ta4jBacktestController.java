package com.okx.trading.controller;

import com.alibaba.fastjson.JSONObject;
import com.okx.trading.model.common.ApiResponse;
import com.okx.trading.model.dto.BacktestResultDTO;
import com.okx.trading.model.entity.*;
import com.okx.trading.model.dto.StrategyUpdateRequestDTO;
import com.okx.trading.service.*;
import com.okx.trading.service.impl.DeepSeekApiService;
import com.okx.trading.service.impl.DynamicStrategyService;
import com.okx.trading.service.impl.JavaCompilerDynamicStrategyService;
import com.okx.trading.service.impl.SmartDynamicStrategyService;
import com.okx.trading.strategy.RealTimeStrategyManager;
import com.okx.trading.adapter.CandlestickAdapter;
import com.okx.trading.adapter.CandlestickBarSeriesConverter;
import com.okx.trading.service.impl.Ta4jBacktestService;
import com.okx.trading.model.trade.Order;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.math.RoundingMode;

/**
 * Ta4j回测控制器
 * 专门用于Ta4j库的回测及结果存储
 */
@Slf4j
@RestController
@RequestMapping("/api/backtest/ta4j")
@Api(tags = "Ta4j回测控制器", description = "提供基于Ta4j库的策略回测及结果存储接口")
@RequiredArgsConstructor
public class Ta4jBacktestController {

    private final HistoricalDataService historicalDataService;
    private final Ta4jBacktestService ta4jBacktestService;
    private final BacktestTradeService backtestTradeService;
    private final MarketDataService marketDataService;
    private final StrategyInfoService strategyInfoService;
    private final DeepSeekApiService deepSeekApiService;
    private final DynamicStrategyService dynamicStrategyService;
    private final JavaCompilerDynamicStrategyService javaCompilerDynamicStrategyService;
    private final SmartDynamicStrategyService smartDynamicStrategyService;
    private final StrategyConversationService strategyConversationService;
    private final CandlestickBarSeriesConverter barSeriesConverter;
    private final RealTimeOrderService realTimeOrderService;
    private final KlineCacheService klineCacheService;
    private final OkxApiService okxApiService;
    private final TradeController tradeController;
    private final RealTimeStrategyManager realTimeStrategyManager;
    private final RealTimeStrategyService realTimeStrategyService;

    // 线程池
    @Qualifier("tradeIndicatorCalculateScheduler")
    @Autowired
    private ExecutorService scheduler;

    @Qualifier("realTimeTradeIndicatorCalculateScheduler")
    @Autowired
    private ExecutorService realTimeTradeScheduler;

    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");


    @GetMapping("/run")
    @ApiOperation(value = "执行Ta4j策略回测", notes = "使用Ta4j库进行策略回测，可选保存结果")
    public ApiResponse<BacktestResultDTO> runBacktest(
            @ApiParam(value = "交易对", defaultValue = "BTC-USDT", required = true, type = "string") @RequestParam String symbol,
            @ApiParam(value = "时间间隔", defaultValue = "1h", required = true, type = "string") @RequestParam String interval,
            @ApiParam(value = "开始时间 (格式: yyyy-MM-dd HH:mm:ss)",
                    defaultValue = "2018-01-01 00:00:00",
                    example = "2018-01-01 00:00:00",
                    required = true,
                    type = "string")
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @ApiParam(value = "结束时间 (格式: yyyy-MM-dd HH:mm:ss)",
                    defaultValue = "2025-04-01 00:00:00",
                    example = "2025-04-01 00:00:00",
                    required = true,
                    type = "string")
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @ApiParam(value = "策略类型",
                    required = true,
                    example = "BOLLINGER",
                    type = "string")
            @RequestParam String strategyType,
            @ApiParam(value = "策略参数 (以逗号分隔的数字)\n" +
                    "- SMA策略参数: 短期均线周期,长期均线周期 (例如：5,20)\n" +
                    "- EMA策略参数: 短期均线周期,长期均线周期 (例如：9,21)\n" +
                    "- 不传或传空字符串将使用默认参数",
                    required = false,
                    example = "20,2.0",
                    type = "string")
            @RequestParam(required = false) String strategyParams,
            @ApiParam(value = "初始资金",
                    defaultValue = "100000",
                    required = true,
                    type = "number",
                    format = "decimal")
            @RequestParam BigDecimal initialAmount,
            @ApiParam(value = "交易手续费率",
                    defaultValue = "0.001",
                    required = false,
                    type = "number",
                    format = "decimal")
            @RequestParam(required = false, defaultValue = "0.001") BigDecimal feeRatio,
            @ApiParam(value = "是否保存结果",
                    required = true,
                    defaultValue = "true",
                    type = "boolean")
            @RequestParam(defaultValue = "true") boolean saveResult) {

        log.info("开始执行Ta4j回测，交易对: {}, 间隔: {}, 时间范围: {} - {}, 策略: {}, 参数: {}, 初始资金: {}, 手续费率: {}",
                symbol, interval, startTime, endTime, strategyType, strategyParams, initialAmount, feeRatio);

        try {

            // 获取历史数据
            List<CandlestickEntity> candlesticks = historicalDataService.fetchAndSaveHistoryWithIntegrityCheck(symbol, interval, startTime.format(dateFormat), endTime.format(dateFormat));

            // 获取基准数据
            List<CandlestickEntity> benchmarkCandlesticks = historicalDataService.fetchAndSaveHistoryWithIntegrityCheck("BTC-USDT", interval, startTime.format(dateFormat), endTime.format(dateFormat));

            if (candlesticks == null || candlesticks.isEmpty()) {
                return ApiResponse.error(404, "未找到指定条件的历史数据");
            }
            // 生成唯一的系列名称
            String seriesName = CandlestickAdapter.getSymbol(candlesticks.get(0)) + "_" + CandlestickAdapter.getIntervalVal(candlesticks.get(0));
            // 使用转换器将蜡烛图实体转换为条形系列
            BarSeries series = barSeriesConverter.convert(candlesticks, seriesName);

            StrategyInfoEntity strategy = strategyInfoService.getStrategyByCode(strategyType).get();


            // 执行回测
            BacktestResultDTO result = ta4jBacktestService.backtest(series, benchmarkCandlesticks, strategyType, initialAmount, feeRatio, interval);

            result.setStrategyName(strategy.getStrategyName());
            result.setStrategyCode(strategy.getStrategyCode());

            // 如果需要保存结果到数据库
            if (saveResult && result.isSuccess()) {
                // 保存交易明细
                String backtestId = backtestTradeService.saveBacktestTrades(symbol, result, strategyParams);
                result.setBacktestId(backtestId);

                // 保存汇总信息
                backtestTradeService.saveBacktestSummary(result, strategyParams, symbol, interval, startTime, endTime, backtestId);

                result.setParameterDescription(result.getParameterDescription() + " (BacktestID: " + backtestId + ")");

                // 打印回测ID信息
                log.info("回测结果已保存，回测ID: {}", backtestId);
            }

            // 打印总体执行信息
            if (result.isSuccess()) {
                log.info("回测执行成功 - {} {}，交易次数: {}，总收益率: {}%，总手续费: {}",
                        result.getStrategyName(),
                        result.getParameterDescription(),
                        result.getNumberOfTrades(),
                        result.getTotalReturn().multiply(new BigDecimal("100")),
                        result.getTotalFee());
            } else {
                log.warn("回测执行失败 - 错误信息: {}", result.getErrorMessage());
            }

            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("回测过程中发生错误: {}", e.getMessage(), e);
            return ApiResponse.error(500, "回测过程中发生错误: " + e.getMessage());
        }
    }

    @GetMapping("/run-all")
    @ApiOperation(value = "执行所有策略的批量回测", notes = "获取所有支持的策略并对每个策略执行回测")
    public ApiResponse<Map<String, Object>> runAllStrategiesBacktest(
            @ApiParam(value = "交易对", defaultValue = "BTC-USDT", required = true, type = "string") @RequestParam String symbol,
            @ApiParam(value = "时间间隔", defaultValue = "1h", required = true, type = "string") @RequestParam String interval,
            @ApiParam(value = "开始时间 (格式: yyyy-MM-dd HH:mm:ss)",
                    defaultValue = "2023-01-01 00:00:00",
                    example = "2023-01-01 00:00:00",
                    required = true,
                    type = "string")
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @ApiParam(value = "结束时间 (格式: yyyy-MM-dd HH:mm:ss)",
                    defaultValue = "2023-12-31 23:59:59",
                    example = "2023-12-31 23:59:59",
                    required = true,
                    type = "string")
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @ApiParam(value = "初始资金",
                    defaultValue = "100000",
                    required = true,
                    type = "number",
                    format = "decimal")
            @RequestParam BigDecimal initialAmount,
            @ApiParam(value = "交易手续费率",
                    defaultValue = "0.001",
                    required = false,
                    type = "number",
                    format = "decimal")
            @RequestParam(required = false, defaultValue = "0.001") BigDecimal feeRatio,
            @ApiParam(value = "是否保存结果",
                    required = true,
                    defaultValue = "true",
                    type = "boolean")
            @RequestParam(defaultValue = "true") boolean saveResult,
            @ApiParam(value = "并行线程数",
                    required = false,
                    defaultValue = "4",
                    type = "integer")
            @RequestParam(required = false, defaultValue = "4") int threadCount) {

        log.info("开始执行所有策略的批量回测，交易对: {}, 间隔: {}, 时间范围: {} - {}, 初始资金: {}, 手续费率: {}, 并行线程数: {}",
                symbol, interval, startTime, endTime, initialAmount, feeRatio, threadCount);

        // 生成唯一的批量回测ID
        String batchBacktestId = UUID.randomUUID().toString();
        log.info("生成批量回测ID: {}", batchBacktestId);

        // 存储所有回测结果
        List<Map<String, Object>> allResults = Collections.synchronizedList(new ArrayList<>());

        try {
            // 获取历史数据
            List<CandlestickEntity> candlesticks = historicalDataService.getHistoricalData(symbol, interval, startTime, endTime);
            if (candlesticks == null || candlesticks.isEmpty()) {
                return ApiResponse.error(404, "未找到指定条件的历史数据");
            }
            // 获取基准数据
            List<CandlestickEntity> benchmarkCandlesticks = historicalDataService.getHistoricalData("BTC-USDT", interval, startTime, endTime);

            // 生成唯一的系列名称
            String seriesName = CandlestickAdapter.getSymbol(candlesticks.get(0)) + "_" + CandlestickAdapter.getIntervalVal(candlesticks.get(0));

            // 使用转换器将蜡烛图实体转换为条形系列
            BarSeries series = barSeriesConverter.convert(candlesticks, seriesName);

            // 获取所有支持的策略
            Map<String, Map<String, Object>> strategiesInfo = strategyInfoService.getStrategiesInfo();
            List<String> strategyCodes = new ArrayList<>(strategiesInfo.keySet());

            log.info("找到{}个策略，准备执行批量回测", strategyCodes.size());

            // 创建线程池
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            // 创建回测任务
            for (String strategyCode : strategyCodes) {
                Map<String, Object> strategyDetails = strategiesInfo.get(strategyCode);
                String defaultParams = (String) strategyDetails.get("default_params");

                // 创建异步任务
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    String currentStrategyCode = strategyCode;
                    try {
                        log.info("开始回测策略: {}", currentStrategyCode);

                        // 执行回测 - 添加额外的异常处理
                        BacktestResultDTO result = null;
                        try {
                            result = ta4jBacktestService.backtest(series, benchmarkCandlesticks, currentStrategyCode, initialAmount, feeRatio, interval);
                        } catch (Exception backtestException) {
                            log.error("策略 {} 回测执行失败: {}", currentStrategyCode, backtestException.getMessage());
                            // 创建一个失败的结果对象
                            result = new BacktestResultDTO();
                            result.setSuccess(false);
                            result.setErrorMessage("回测执行失败: " + backtestException.getMessage());
                        }

                        if (result == null) {
                            result = new BacktestResultDTO();
                            result.setSuccess(false);
                            result.setErrorMessage("回测结果为空");
                        }

                        result.setStrategyName((String) strategyDetails.get("name"));
                        result.setStrategyCode((String) strategyDetails.get("strategy_code"));

                        // 如果需要保存结果到数据库
                        if (saveResult && result.isSuccess()) {
                            try {
                                // 保存交易明细
                                String backtestId = backtestTradeService.saveBacktestTrades(symbol, result, defaultParams);
                                result.setBacktestId(backtestId);

                                // 保存汇总信息，包含批量回测ID
                                backtestTradeService.saveBacktestSummary(
                                        result, defaultParams, symbol, interval, startTime, endTime, backtestId, batchBacktestId);

                                result.setParameterDescription(result.getParameterDescription() + " (BacktestID: " + backtestId + ", BatchID: " + batchBacktestId + ")");
                            } catch (Exception saveException) {
                                log.error("策略 {} 保存结果失败: {}", currentStrategyCode, saveException.getMessage());
                                // 不影响回测结果，只是保存失败
                            }
                        }

                        // 记录结果
                        Map<String, Object> resultMap = new HashMap<>();
                        resultMap.put("strategy_code", currentStrategyCode);
                        resultMap.put("strategy_name", strategyDetails.get("name"));
                        resultMap.put("success", result.isSuccess());

                        if (result.isSuccess()) {
                            resultMap.put("total_return", result.getTotalReturn() != null ? result.getTotalReturn() : BigDecimal.ZERO);
                            resultMap.put("number_of_trades", result.getNumberOfTrades());
                            resultMap.put("win_rate", result.getWinRate() != null ? result.getWinRate() : BigDecimal.ZERO);
                            resultMap.put("profit_factor", result.getProfitFactor() != null ? result.getProfitFactor() : BigDecimal.ZERO);
                            resultMap.put("sharpe_ratio", result.getSharpeRatio() != null ? result.getSharpeRatio() : BigDecimal.ZERO);
                            resultMap.put("max_drawdown", result.getMaxDrawdown() != null ? result.getMaxDrawdown() : BigDecimal.ZERO);
                            resultMap.put("backtest_id", result.getBacktestId());

                            log.info("策略 {} 回测成功 - 收益率: {}%, 交易次数: {}, 胜率: {}%",
                                    strategyDetails.get("name"),
                                    result.getTotalReturn() != null ? result.getTotalReturn().multiply(new BigDecimal("100")).toString() : "0",
                                    String.valueOf(result.getNumberOfTrades()),
                                    result.getWinRate() != null ? result.getWinRate().multiply(new BigDecimal("100")).toString() : "0");
                        } else {
                            resultMap.put("error", result.getErrorMessage() != null ? result.getErrorMessage() : "未知错误");
                            log.warn("策略 {} 回测失败 - 错误信息: {}", currentStrategyCode, result.getErrorMessage());
                        }

                        allResults.add(resultMap);

                    } catch (Exception e) {
                        log.error("策略 {} 回测过程中发生未捕获错误: {}", currentStrategyCode, e.getMessage(), e);
                        Map<String, Object> errorResult = new HashMap<>();
                        errorResult.put("strategy_code", currentStrategyCode);
                        errorResult.put("strategy_name", strategyDetails.get("name"));
                        errorResult.put("success", false);
                        errorResult.put("error", "未捕获错误: " + e.getMessage());
                        allResults.add(errorResult);
                    }
                }, scheduler);

                futures.add(future);
            }

            // 等待所有任务完成，设置单策略5秒超时时间
            for (int i = 0; i < futures.size(); i++) {
                CompletableFuture<Void> future = futures.get(i);
                String strategyCode = strategyCodes.get(i);
                try {
                    future.get(30, TimeUnit.SECONDS); // 单策略5秒超时
                } catch (TimeoutException e) {
                    log.warn("策略 {} 回测超时（30秒），取消该策略的回测任务", strategyCode);
                    future.cancel(true);

                    // 添加超时错误结果
                    Map<String, Object> timeoutResult = new HashMap<>();
                    timeoutResult.put("strategy_code", strategyCode);
                    timeoutResult.put("strategy_name", "超时策略: " + strategyCode);
                    timeoutResult.put("success", false);
                    timeoutResult.put("error", "策略回测超时（5秒）");
                    allResults.add(timeoutResult);
                } catch (ExecutionException e) {
                    log.error("策略 {} 执行异常: {}", strategyCode, e.getMessage(), e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("策略 {} 执行被中断: {}", strategyCode, e.getMessage(), e);
                }
            }

            // 按收益率排序结果
            allResults.sort((a, b) -> {
                // 首先按成功状态排序，失败的排在后面
                boolean successA = (boolean) a.get("success");
                boolean successB = (boolean) b.get("success");

                if (!successA && !successB) return 0; // 两个都失败，认为相等
                if (!successA) return 1;  // a失败，b成功，a排后面
                if (!successB) return -1; // a成功，b失败，a排前面

                // 两个都成功，按收益率排序
                BigDecimal returnA = (BigDecimal) a.get("total_return");
                BigDecimal returnB = (BigDecimal) b.get("total_return");

                // 处理null值情况
                if (returnA == null && returnB == null) return 0;
                if (returnA == null) return 1;  // null值排后面
                if (returnB == null) return -1; // null值排后面

                return returnB.compareTo(returnA); // 降序排列
            });

            long successCount = allResults.stream().filter(r -> (boolean) r.get("success")).count();
            Double totalReturn = allResults.stream()
                    .filter(r -> (boolean) r.get("success"))
                    .map(x -> {
                        BigDecimal ret = (BigDecimal) x.get("total_return");
                        return ret != null ? ret.doubleValue() : 0.0;
                    })
                    .reduce(Double::sum)
                    .orElse(0.0);

            // 构建响应结果
            Map<String, Object> response = new HashMap<>();
            response.put("batch_backtest_id", batchBacktestId);
            response.put("total_strategies", strategyCodes.size());
            response.put("successful_backtests", successCount);
            response.put("failed_backtests", (long) allResults.size() - successCount);

            if (!allResults.isEmpty() && (boolean) allResults.get(0).get("success")) {
                response.put("max_return", allResults.get(0).get("total_return"));
                response.put("max_return_strategy", allResults.get(0).get("strategy_name"));
            } else {
                response.put("max_return", BigDecimal.ZERO);
                response.put("max_return_strategy", "无");
            }

            response.put("avg_return", successCount > 0 ? totalReturn / successCount : 0.0);
            response.put("results", allResults);

            log.info("批量回测完成，批量ID: {}, 成功: {}, 失败: {}",
                    batchBacktestId,
                    String.valueOf(response.get("successful_backtests")),
                    String.valueOf(response.get("failed_backtests")));

            return ApiResponse.success(response);

        } catch (Exception e) {
            log.error("批量回测过程中发生严重错误: {}", e.getMessage(), e);

            // 即使发生严重错误，也尝试返回已完成的结果
            if (!allResults.isEmpty()) {
                Map<String, Object> partialResponse = new HashMap<>();
                partialResponse.put("batch_backtest_id", batchBacktestId);
                partialResponse.put("total_strategies", allResults.size());
                partialResponse.put("successful_backtests", allResults.stream().filter(r -> (boolean) r.get("success")).count());
                partialResponse.put("failed_backtests", allResults.stream().filter(r -> !(boolean) r.get("success")).count());
                partialResponse.put("results", allResults);
                partialResponse.put("error", "部分完成，发生错误: " + e.getMessage());

                return ApiResponse.success(partialResponse);
            }

            return ApiResponse.error(500, "批量回测过程中发生错误: " + e.getMessage());
        }
    }

    @GetMapping("/strategies")
    @ApiOperation(value = "获取支持的策略类型和参数说明", notes = "返回系统支持的所有策略类型和对应的参数说明")
    public ApiResponse<Map<String, Map<String, Object>>> getStrategies() {
        try {
            // 从数据库中获取所有策略信息
            Map<String, Map<String, Object>> strategies = strategyInfoService.getStrategiesInfo();

            // 为每个策略添加available字段，基于最后一次对话的compile_error字段
            for (Map.Entry<String, Map<String, Object>> entry : strategies.entrySet()) {
                Map<String, Object> strategyInfo = entry.getValue();
                String strategyCode = (String) strategyInfo.get("strategy_code");
                strategyInfo.put("best_return", 0.0);
                String strategyIdStr = (String) strategyInfo.get("id");
                String loadError = (String) strategyInfo.get("load_error");

                if (strategyIdStr != null && !strategyIdStr.isEmpty()) {
                    try {
                        Long strategyId = Long.valueOf(strategyIdStr);
                        // 查询最后一次对话记录
                        StrategyConversationEntity lastConversation = strategyConversationService.getLastConversation(strategyId);

                        // 根据compile_error字段设置available
                        boolean available = true;
                        if (lastConversation != null && lastConversation.getCompileError() != null && !lastConversation.getCompileError().trim().isEmpty()) {
                            available = false;
                        }
                        //加载错误也设置false
                        if (StringUtils.isNotBlank(loadError)) {
                            available = false;
                        }
                        strategyInfo.put("available", available);

                    } catch (NumberFormatException e) {
                        log.warn("策略ID格式错误: {}", strategyIdStr);
                        strategyInfo.put("available", "true"); // 默认为true
                    }
                } else {
                    strategyInfo.put("available", "true"); // 默认为true
                }
            }

            return ApiResponse.success(strategies);
        } catch (Exception e) {
            log.error("获取策略信息出错: {}", e.getMessage(), e);
            return ApiResponse.error(500, "获取策略信息出错: " + e.getMessage());
        }
    }

    @GetMapping("/history")
    @ApiOperation(value = "获取回测历史记录", notes = "获取所有已保存的回测历史ID")
    public ApiResponse<List<String>> getBacktestHistory() {
        try {
            List<String> backtestIds = backtestTradeService.getAllBacktestIds();
            return ApiResponse.success(backtestIds);
        } catch (Exception e) {
            log.error("获取回测历史记录出错: {}", e.getMessage(), e);
            return ApiResponse.error(500, "获取回测历史记录出错: " + e.getMessage());
        }
    }

    @GetMapping("/detail/{backtestId}")
    @ApiOperation(value = "获取回测详情", notes = "获取指定回测ID的详细交易记录")
    public ApiResponse<List<BacktestTradeEntity>> getBacktestDetail(
            @ApiParam(value = "回测ID", required = true, type = "string") @PathVariable String backtestId) {
        try {
            List<BacktestTradeEntity> trades = backtestTradeService.getTradesByBacktestId(backtestId);
            if (trades.isEmpty()) {
                return ApiResponse.error(404, "未找到指定回测ID的交易记录");
            }
            return ApiResponse.success(trades);
        } catch (Exception e) {
            log.error("获取回测详情出错: {}", e.getMessage(), e);
            return ApiResponse.error(500, "获取回测详情出错: " + e.getMessage());
        }
    }

    @DeleteMapping("/delete/{backtestId}")
    @ApiOperation(value = "删除回测记录", notes = "删除指定回测ID的所有交易记录")
    public ApiResponse<Void> deleteBacktestRecord(
            @ApiParam(value = "回测ID", required = true, type = "string") @PathVariable String backtestId) {
        try {
            backtestTradeService.deleteBacktestRecords(backtestId);
            return ApiResponse.success("成功删除回测记录", null);
        } catch (Exception e) {
            log.error("删除回测记录出错: {}", e.getMessage(), e);
            return ApiResponse.error(500, "删除回测记录出错: " + e.getMessage());
        }
    }

    @GetMapping("/summaries")
    @ApiOperation(value = "获取所有回测汇总信息", notes = "获取所有已保存的回测汇总信息")
    public ApiResponse<List<BacktestSummaryEntity>> getAllBacktestSummaries() {
        try {
            List<BacktestSummaryEntity> summaries = backtestTradeService.getAllBacktestSummaries();
            return ApiResponse.success(summaries);
        } catch (Exception e) {
            log.error("获取回测汇总信息出错: {}", e.getMessage(), e);
            return ApiResponse.error(500, "获取回测汇总信息出错: " + e.getMessage());
        }
    }

    @GetMapping("/summary/{backtestId}")
    @ApiOperation(value = "获取回测汇总信息", notes = "根据回测ID获取回测汇总信息")
    public ApiResponse<BacktestSummaryEntity> getBacktestSummary(
            @ApiParam(value = "回测ID", required = true, type = "string") @PathVariable String backtestId) {
        try {
            Optional<BacktestSummaryEntity> summary = backtestTradeService.getBacktestSummaryById(backtestId);
            if (summary.isPresent()) {
                return ApiResponse.success(summary.get());
            } else {
                return ApiResponse.error(404, "未找到指定回测ID的汇总信息");
            }
        } catch (Exception e) {
            log.error("获取回测汇总信息出错: {}", e.getMessage(), e);
            return ApiResponse.error(500, "获取回测汇总信息出错: " + e.getMessage());
        }
    }

    @GetMapping("/summaries/strategy/{strategyName}")
    @ApiOperation(value = "根据策略名称获取回测汇总信息", notes = "获取特定策略的所有回测汇总信息")
    public ApiResponse<List<BacktestSummaryEntity>> getBacktestSummariesByStrategy(
            @ApiParam(value = "策略名称", required = true, type = "string") @PathVariable String strategyName) {
        try {
            List<BacktestSummaryEntity> summaries = backtestTradeService.getBacktestSummariesByStrategy(strategyName);
            if (summaries.isEmpty()) {
                return ApiResponse.error(404, "未找到该策略的回测汇总信息");
            }
            return ApiResponse.success(summaries);
        } catch (Exception e) {
            log.error("获取策略回测汇总信息出错: {}", e.getMessage(), e);
            return ApiResponse.error(500, "获取策略回测汇总信息出错: " + e.getMessage());
        }
    }

    @GetMapping("/summaries/symbol/{symbol}")
    @ApiOperation(value = "根据交易对获取回测汇总信息", notes = "获取特定交易对的所有回测汇总信息")
    public ApiResponse<List<BacktestSummaryEntity>> getBacktestSummariesBySymbol(
            @ApiParam(value = "交易对", required = true, type = "string") @PathVariable String symbol) {
        try {
            List<BacktestSummaryEntity> summaries = backtestTradeService.getBacktestSummariesBySymbol(symbol);
            if (summaries.isEmpty()) {
                return ApiResponse.error(404, "未找到该交易对的回测汇总信息");
            }
            return ApiResponse.success(summaries);
        } catch (Exception e) {
            log.error("获取交易对回测汇总信息出错: {}", e.getMessage(), e);
            return ApiResponse.error(500, "获取交易对回测汇总信息出错: " + e.getMessage());
        }
    }

    @GetMapping("/summaries/batch/{batchBacktestId}")
    @ApiOperation(value = "根据批量回测ID获取回测汇总信息", notes = "获取同一批次执行的所有回测汇总信息")
    public ApiResponse<List<BacktestSummaryEntity>> getBacktestSummariesByBatchId(
            @ApiParam(value = "批量回测ID", required = true, type = "string") @PathVariable String batchBacktestId) {
        try {
            List<BacktestSummaryEntity> summaries = backtestTradeService.getBacktestSummariesByBatchId(batchBacktestId);
            Collections.sort(summaries);
            if (summaries.isEmpty()) {
                return ApiResponse.error(404, "未找到该批次的回测汇总信息");
            }
            return ApiResponse.success(summaries);
        } catch (Exception e) {
            log.error("获取批次回测汇总信息出错: {}", e.getMessage(), e);
            return ApiResponse.error(500, "获取批次回测汇总信息出错: " + e.getMessage());
        }
    }

    @GetMapping("/summaries/batch-statistics")
    @ApiOperation(value = "获取批量回测统计信息", notes = "按批量回测ID聚合统计回测结果，包括回测数量、最大收益和回测ID列表等")
    public ApiResponse<List<Map<String, Object>>> getBatchBacktestStatistics() {
        try {
            // 获取所有回测汇总信息
            List<BacktestSummaryEntity> allSummaries = backtestTradeService.getAllBacktestSummaries();

            // 过滤掉batchBacktestId为空的数据，并按batchBacktestId分组
            Map<String, List<BacktestSummaryEntity>> batchGroups = allSummaries.stream()
                    .filter(summary -> summary.getBatchBacktestId() != null && !summary.getBatchBacktestId().isEmpty())
                    .collect(Collectors.groupingBy(BacktestSummaryEntity::getBatchBacktestId));

            // 计算每个批次的统计信息
            List<Map<String, Object>> batchStatistics = new ArrayList<>();
            for (Map.Entry<String, List<BacktestSummaryEntity>> entry : batchGroups.entrySet()) {
                String batchId = entry.getKey();
                List<BacktestSummaryEntity> batchSummaries = entry.getValue();

                // 计算统计信息
                int backtestCount = batchSummaries.size();

                // 找出最大收益率的回测
                BacktestSummaryEntity bestBacktest = batchSummaries.stream()
                        .max(Comparator.comparing(BacktestSummaryEntity::getTotalReturn))
                        .orElse(null);

                // 计算平均收益率
                BigDecimal avgReturn = batchSummaries.stream()
                        .map(BacktestSummaryEntity::getTotalReturn)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(new BigDecimal(backtestCount), 4, RoundingMode.HALF_UP);

                // 计算年华平均收益率
                BigDecimal avgAnnualReturn = batchSummaries.stream()
                        .map(BacktestSummaryEntity::getAnnualizedReturn).filter(x -> x != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(new BigDecimal(backtestCount), 4, RoundingMode.HALF_UP);

                // 平均交易次数
                int avgTradeNum = batchSummaries.stream().map(x -> x.getNumberOfTrades()).reduce(Integer::sum).get() / backtestCount;

                // 收集所有回测ID
                List<String> backtestIds = batchSummaries.stream()
                        .map(BacktestSummaryEntity::getBacktestId)
                        .collect(Collectors.toList());

                // 创建批次统计信息
                Map<String, Object> batchStat = new HashMap<>();
                batchStat.put("batch_backtest_id", batchId);
                batchStat.put("backtest_count", backtestCount);
                batchStat.put("avg_return", avgReturn);
                batchStat.put("avg_annual_return", avgAnnualReturn);
                batchStat.put("avg_trade_num", avgTradeNum);

                if (bestBacktest != null) {
                    batchStat.put("max_return", bestBacktest.getTotalReturn());
                    batchStat.put("strategy_name", bestBacktest.getStrategyName());
                    batchStat.put("symbol", bestBacktest.getSymbol());
                    batchStat.put("create_time", bestBacktest.getCreateTime());
                    batchStat.put("start_time", bestBacktest.getStartTime());
                    batchStat.put("end_time", bestBacktest.getEndTime());
                    batchStat.put("interval_val", bestBacktest.getIntervalVal());
                }

                batchStat.put("backtest_ids", backtestIds);

                batchStatistics.add(batchStat);
            }

            // 按创建时间降序排序
            batchStatistics.sort((a, b) -> {
                LocalDateTime timeA = (LocalDateTime) a.get("create_time");
                LocalDateTime timeB = (LocalDateTime) b.get("create_time");
                return timeB.compareTo(timeA); // 降序排序
            });

            return ApiResponse.success(batchStatistics);
        } catch (Exception e) {
            log.error("获取批量回测统计信息出错: {}", e.getMessage(), e);
            return ApiResponse.error(500, "获取批量回测统计信息出错: " + e.getMessage());
        }
    }

    @PostMapping("/generate-strategy")
    @ApiOperation(value = "批量生成AI策略", notes = "通过DeepSeek API根据策略描述生成完整的交易策略信息并保存到数据库，支持批量生成，每行一个策略描述，AI一次性返回所有策略")
    public ApiResponse<List<StrategyInfoEntity>> generateStrategy(
            @ApiParam(value = "策略描述，支持多行，每行一个策略描述", required = true,
                    example = "基于双均线RSI组合的交易策略，使用9日和26日移动平均线交叉信号，结合RSI指标过滤信号\n基于MACD和布林带的组合策略\n基于KDJ指标的超买超卖策略")
            @RequestBody String descriptions) {

        log.info("开始批量生成AI策略，策略描述: {}", descriptions);

        // 按行分割策略描述
        String[] descriptionLines = descriptions.split("\n");
        // 过滤空行
        List<String> validDescriptions = new ArrayList<>();
        for (String desc : descriptionLines) {
            String trimmed = desc.trim();
            if (!trimmed.isEmpty()) {
                validDescriptions.add(trimmed);
            }
        }

        if (validDescriptions.isEmpty()) {
            return ApiResponse.error(400, "策略描述不能为空");
        }

        List<StrategyInfoEntity> generatedStrategies = new ArrayList<>();
        List<String> errorMessages = new ArrayList<>();

        try {

            // 一次性调用AI生成所有策略
            log.info("调用AI一次性生成{}个策略", validDescriptions.size());
            com.alibaba.fastjson.JSONArray batchStrategyInfos = deepSeekApiService.generateBatchCompleteStrategyInfo(validDescriptions.toArray(new String[0]));

            if (batchStrategyInfos == null || batchStrategyInfos.size() == 0) {
                return ApiResponse.error(500, "AI未返回任何策略信息");
            }

            log.info("AI返回了{}个策略信息，开始处理", batchStrategyInfos.size());

            // 处理每个策略信息
            for (int i = 0; i < batchStrategyInfos.size(); i++) {
                try {
                    JSONObject strategyInfo = batchStrategyInfos.getJSONObject(i);
                    String originalDescription = i < validDescriptions.size() ? validDescriptions.get(i) : "未知描述";

                    // 从AI返回的信息中提取各个字段
                    String strategyName = strategyInfo.getString("strategyName");
                    String strategyId = strategyInfo.getString("strategyId");
                    String strategyDescription = strategyInfo.getString("description");
                    String strategyComments = strategyInfo.getString("comments");
                    String category = strategyInfo.getString("category");
                    String paramsDesc = strategyInfo.getJSONObject("paramsDesc").toJSONString();
                    String defaultParams = strategyInfo.getJSONObject("defaultParams").toJSONString();
                    String generatedCode = strategyInfo.getString("strategyCode");

                    // 检查策略代码是否已存在，如果存在则添加时间戳后缀确保唯一性
                    String uniqueStrategyId = strategyId;
                    int counter = 1;
                    while (strategyInfoService.existsByStrategyCode(uniqueStrategyId)) {
                        uniqueStrategyId = strategyId + "_" + System.currentTimeMillis() + "_" + counter;
                        counter++;
                    }

                    // 创建策略实体
                    StrategyInfoEntity strategyEntity = StrategyInfoEntity.builder()
                            .strategyCode(uniqueStrategyId)
                            .strategyName(strategyName)
                            .description(strategyDescription)
                            .comments(strategyComments)
                            .category(category)
                            .paramsDesc(paramsDesc)
                            .defaultParams(defaultParams)
                            .sourceCode(generatedCode)
                            .build();

                    // 保存到数据库
                    StrategyInfoEntity savedStrategy = strategyInfoService.saveStrategy(strategyEntity);

                    // 编译并动态加载策略 - 使用智能编译服务
                    String compileError = null;
                    try {
                        smartDynamicStrategyService.compileAndLoadStrategy(generatedCode, savedStrategy);
                    } catch (Exception compileException) {
                        compileError = compileException.getMessage();
                        savedStrategy.setLoadError(compileError);
                        strategyInfoService.saveStrategy(savedStrategy);
                        log.warn("智能编译服务失败，保存错误记录: {}", compileError);
                    }

                    // 保存完整的对话记录到strategy_conversation表（包含编译错误信息）
                    StrategyConversationEntity conversation = StrategyConversationEntity.builder()
                            .strategyId(savedStrategy.getId())
                            .userInput(originalDescription)
                            .aiResponse(strategyInfo.toJSONString())
                            .conversationType("generate")
                            .compileError(compileError)
                            .build();
                    strategyConversationService.saveConversation(conversation);

                    generatedStrategies.add(savedStrategy);
                    log.info("第{}个AI策略生成成功，策略代码: {}, 策略名称: {}", i + 1, uniqueStrategyId, strategyName);

                } catch (Exception e) {
                    String originalDescription = i < validDescriptions.size() ? validDescriptions.get(i) : "未知描述";
                    String errorMsg = String.format("第%d个策略处理失败: %s, 描述: %s", i + 1, e.getMessage(), originalDescription);
                    log.error(errorMsg, e);
                    errorMessages.add(errorMsg);

                    // 创建一个错误的策略实体用于返回
                    StrategyInfoEntity errorStrategy = StrategyInfoEntity.builder()
                            .strategyCode("ERROR_" + (i + 1))
                            .strategyName("生成失败")
                            .description(originalDescription)
                            .comments("生成失败: " + e.getMessage())
                            .category("错误")
                            .paramsDesc("{}")
                            .defaultParams("{}")
                            .sourceCode("// 生成失败")
                            .build();
                    generatedStrategies.add(errorStrategy);
                }
            }

        } catch (Exception e) {
            log.error("批量生成策略失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "批量生成策略失败: " + e.getMessage());
        }

        log.info("批量策略生成完成，成功: {}, 失败: {}",
                generatedStrategies.size() - errorMessages.size(), errorMessages.size());

        if (!errorMessages.isEmpty()) {
            log.warn("部分策略生成失败: {}", String.join("; ", errorMessages));
        }

        return ApiResponse.success(generatedStrategies);
    }


    @PostMapping("/update-strategy")
    @ApiOperation(value = "更新策略", notes = "更新策略信息和源代码，并重新加载到系统中")
    public ApiResponse<StrategyInfoEntity> updateStrategy(
            @ApiParam(value = "更新策略文字描述", required = true, example = "更新策略，提高胜率")
            @RequestParam String description,
            @ApiParam(value = "策略唯一ID", required = true, example = "2681")
            @RequestParam Long id) {

        StrategyUpdateRequestDTO request = new StrategyUpdateRequestDTO();
        request.setId(id);
        request.setDescription(description);

        log.info("开始更新策略，策略ID: {}", request.getId());

        try {
            // 查找现有策略
            Optional<StrategyInfoEntity> existingStrategyOpt = strategyInfoService.getStrategyById(request.getId());
            if (!existingStrategyOpt.isPresent()) {
                return ApiResponse.error(404, "策略不存在: " + request.getId());
            }

            if (existingStrategyOpt.get().getSourceCode().isEmpty()) {
                return ApiResponse.error(405, "预添加策略不支持更新: " + request.getId());
            }

            StrategyInfoEntity existingStrategy = existingStrategyOpt.get();

            // 获取历史对话记录
            String conversationContext = strategyConversationService.buildConversationContext(existingStrategy.getId());

            // 调用DeepSeek API生成完整的策略信息
            com.alibaba.fastjson.JSONObject strategyInfo = deepSeekApiService.updateStrategyInfo(request.getDescription(), JSONObject.toJSONString(existingStrategy), conversationContext);

            // 从AI返回的信息中提取各个字段
            String aiStrategyName = strategyInfo.getString("strategyName");
            String aiCategory = strategyInfo.getString("category");
            String aiDescription = strategyInfo.getString("description");
            String aiComments = strategyInfo.getString("comments");
            String aiDefaultParams = strategyInfo.getJSONObject("defaultParams").toJSONString();
            String aiParamsDesc = strategyInfo.getJSONObject("paramsDesc").toJSONString();
            String newGeneratedCode = strategyInfo.getString("strategyCode");

            // 更新策略信息（优先使用AI生成的信息，如果请求中有指定则使用请求中的）
            existingStrategy.setDescription(aiDescription);
            existingStrategy.setCategory(aiCategory);
            existingStrategy.setComments(aiComments);
            existingStrategy.setParamsDesc(aiParamsDesc);
            existingStrategy.setDefaultParams(aiDefaultParams);
            existingStrategy.setSourceCode(newGeneratedCode);
            existingStrategy.setUpdateTime(LocalDateTime.now());

            // 保存到数据库
            StrategyInfoEntity updatedStrategy = strategyInfoService.saveStrategy(existingStrategy);

            // 重新编译并加载策略 - 使用智能编译服务
            String compileError = null;
            try {
                smartDynamicStrategyService.compileAndLoadStrategy(newGeneratedCode, updatedStrategy);
            } catch (Exception compileException) {
                compileError = compileException.getMessage();
                log.warn("智能编译服务失败，保存错误记录: {}", compileError);
            }

            // 保存完整的对话记录到strategy_conversation表（包含编译错误信息）
            StrategyConversationEntity conversation = StrategyConversationEntity.builder()
                    .strategyId(existingStrategy.getId())
                    .userInput(request.getDescription())
                    .aiResponse(strategyInfo.toJSONString())
                    .conversationType("update")
                    .compileError(compileError)
                    .build();
            strategyConversationService.saveConversation(conversation);

            log.info("策略更新成功，策略代码: {}", existingStrategy.getStrategyCode());
            return ApiResponse.success(updatedStrategy);

        } catch (Exception e) {
            log.error("更新策略失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "更新策略失败: " + e.getMessage());
        }
    }

    @PostMapping("/reload-dynamic-strategies")
    @ApiOperation(value = "重新加载动态策略", notes = "从数据库重新加载所有动态策略")
    public ApiResponse<String> reloadDynamicStrategies() {

        log.info("开始重新加载动态策略");

        try {
            smartDynamicStrategyService.loadAllDynamicStrategies();
            log.info("使用智能编译服务重新加载动态策略成功");
            return ApiResponse.success("动态策略重新加载成功");

        } catch (Exception e) {
            log.error("重新加载动态策略失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "重新加载动态策略失败: " + e.getMessage());
        }
    }

    @GetMapping("/strategy/{strategyCode}")
    @ApiOperation(value = "获取策略详细信息", notes = "根据策略代码获取策略的详细信息")
    public ApiResponse<StrategyInfoEntity> getStrategyDetail(
            @ApiParam(value = "策略代码", required = true, example = "TEST_CHINESE_001")
            @PathVariable String strategyCode) {

        log.info("获取策略详细信息，策略代码: {}", strategyCode);

        try {
            Optional<StrategyInfoEntity> strategyOpt = strategyInfoService.getStrategyByCode(strategyCode);
            if (!strategyOpt.isPresent()) {
                return ApiResponse.error(404, "策略不存在: " + strategyCode);
            }

            return ApiResponse.success(strategyOpt.get());

        } catch (Exception e) {
            log.error("获取策略详细信息失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "获取策略详细信息失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/delete-strategy/{strategyCode}")
    @ApiOperation(value = "删除策略", notes = "根据策略代码删除策略，包括从数据库和动态策略缓存中移除")
    public ApiResponse<String> deleteStrategy(
            @ApiParam(value = "策略代码", required = true, example = "AI_SMA_009")
            @PathVariable String strategyCode) {

        log.info("开始删除策略，策略代码: {}", strategyCode);

        try {
            // 检查策略是否存在
            Optional<StrategyInfoEntity> existingStrategyOpt = strategyInfoService.getStrategyByCode(strategyCode);
            if (!existingStrategyOpt.isPresent()) {
                return ApiResponse.error(404, "策略不存在: " + strategyCode);
            }

            // 从动态策略缓存中移除
            dynamicStrategyService.removeStrategy(strategyCode);

            // 从数据库中删除
            strategyInfoService.deleteStrategyByCode(strategyCode);

            log.info("策略删除成功，策略代码: {}", strategyCode);
            return ApiResponse.success("策略删除成功: " + strategyCode);

        } catch (Exception e) {
            log.error("删除策略失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "删除策略失败: " + e.getMessage());
        }
    }

    /**
     * 实时策略回测接口
     * 获取实时K线数据和历史300根K线数据进行策略回测
     * 当触发交易信号时，实时调用交易接口创建订单
     */
    @PostMapping("/real-time")
    @ApiOperation(value = "实时策略回测", notes = "基于实时K线数据进行策略回测，触发信号时自动下单")
    public ApiResponse<Map<String, Object>> realTimeBacktest(
            @ApiParam(value = "策略代码", required = true, example = "STOCHASTIC") @RequestParam String strategyCode,
            @ApiParam(value = "交易对", required = true, example = "BTC-USDT") @RequestParam String symbol,
            @ApiParam(value = "时间间隔", required = true, example = "1D") @RequestParam String interval,
            @ApiParam(value = "交易金额", required = false, example = "20") @RequestParam(required = true) BigDecimal tradeAmount) {
        try {

            log.info("开始实时策略回测: strategyCode={}, symbol={}, interval={}", strategyCode, symbol, interval);
            LocalDateTime now = LocalDateTime.now();
            // 1. 验证策略是否存在
            Optional<StrategyInfoEntity> strategyOpt = strategyInfoService.getStrategyByCode(strategyCode);
            if (!strategyOpt.isPresent()) {
                return ApiResponse.error(404, "策略不存在: " + strategyCode);
            }
            StrategyInfoEntity strategy = strategyOpt.get();
            RealTimeStrategyEntity realTimeStrategy = new RealTimeStrategyEntity(strategyCode, symbol, interval, now, tradeAmount.doubleValue(),strategy.getStrategyName());
            realTimeStrategyManager.addStrategy(realTimeStrategy);

            // 4. 初始化实时回测状态
            Map<String, Object> backtestState = new HashMap<>();
            backtestState.put("strategyCode", strategyCode);
            backtestState.put("strategyName", strategy.getStrategyName());
            backtestState.put("symbol", symbol);
            backtestState.put("interval", interval);
            backtestState.put("startTime", now.format(dateFormat));
            backtestState.put("tradeAmount", tradeAmount);


            // 5. 开始实时监控和交易
            CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
                return realTimeStrategyService.executeRealTimeBacktest(backtestState);
            }, realTimeTradeScheduler);

            // 6. 返回初始状态
            Map<String, Object> response = new HashMap<>();
            response.put("message", "实时回测已启动");
            response.put("strategyName", strategy.getStrategyName());
            response.put("strategyCode", strategyCode);
            response.put("symbol", symbol);
            response.put("interval", interval);
            response.put("tradeAmount", tradeAmount);
            response.put("status", "RUNNING");
            response.put("startTime", now.format(dateFormat));

            return ApiResponse.success(response);

        } catch (Exception e) {
            log.error("实时回测启动失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "实时回测启动失败: " + e.getMessage());
        }
    }


    /**
     * 执行交易订单
     */
    private Order executeTradeOrder(String symbol, String side, String orderType, BigDecimal price, BigDecimal amount, Boolean simulated) {
        try {
            return tradeController.createSpotOrder(
                    symbol, orderType, side,
                    "LIMIT".equals(orderType) ? price : null, // 限价单需要价格
                    null, // quantity
                    amount, // amount
                    null, // buyRatio
                    null, // sellRatio
                    null, // clientOrderId
                    null, // postOnly
                    simulated
            ).getData();
        } catch (Exception e) {
            log.error("创建订单失败: symbol={}, side={}, amount={}, error={}", symbol, side, amount, e.getMessage());
            return null;
        }
    }


    /**
     * 获取实时回测订单记录
     */
    @GetMapping("/real-time/orders")
    @ApiOperation(value = "获取实时回测订单记录", notes = "查询指定策略的实时交易订单记录")
    public ApiResponse<List<RealTimeOrderEntity>> getRealTimeOrders(
            @ApiParam(value = "策略代码", required = false) @RequestParam(required = false) String strategyCode,
            @ApiParam(value = "交易对", required = false) @RequestParam(required = false) String symbol,
            @ApiParam(value = "开始时间", required = false) @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @ApiParam(value = "结束时间", required = false) @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        try {
            List<RealTimeOrderEntity> orders;

            if (StringUtils.isNotBlank(strategyCode) && StringUtils.isNotBlank(symbol)) {
                orders = realTimeOrderService.getOrdersByStrategyAndSymbol(strategyCode, symbol);
            } else if (StringUtils.isNotBlank(strategyCode)) {
                orders = realTimeOrderService.getOrdersByStrategy(strategyCode);
            } else if (StringUtils.isNotBlank(symbol)) {
                orders = realTimeOrderService.getOrdersBySymbol(symbol);
            } else if (startTime != null && endTime != null) {
                orders = realTimeOrderService.getOrdersByTimeRange(startTime, endTime);
            } else {
                orders = realTimeOrderService.getAllOrders();
            }

            return ApiResponse.success(orders);

        } catch (Exception e) {
            log.error("获取实时订单记录失败: {}", e.getMessage(), e);
            return ApiResponse.error(500, "获取实时订单记录失败: " + e.getMessage());
        }
    }

    /**
     * 计算最近完整周期的开始时间
     *
     * @param now             当前时间
     * @param interval        周期字符串 (如: 1m, 5m, 1H, 1D, 1W, 1M)
     * @param intervalMinutes 周期对应的分钟数
     * @return 最近完整周期的开始时间
     */
    private LocalDateTime calculateLastCompletePeriodStart(LocalDateTime now, String interval, long intervalMinutes) {
        String unit = interval.substring(interval.length() - 1);
        int amount = Integer.parseInt(interval.substring(0, interval.length() - 1));

        switch (unit) {
            case "m": // 分钟
                // 对齐到最近的完整分钟周期
                int currentMinute = now.getMinute();
                int alignedMinute = (currentMinute / amount) * amount;
                return now.withMinute(alignedMinute).withSecond(0).withNano(0).minusMinutes(amount);

            case "H": // 小时
                // 对齐到最近的完整小时周期
                int currentHour = now.getHour();
                int alignedHour = (currentHour / amount) * amount;
                return now.withHour(alignedHour).withMinute(0).withSecond(0).withNano(0).minusHours(amount);

            case "D": // 天
                // 对齐到最近的完整天周期 (UTC 0点开始)
                return now.toLocalDate().atStartOfDay().minusDays(1);

            case "W": // 周
                // 对齐到最近的完整周周期 (周一开始)
                LocalDate currentDate = now.toLocalDate();
                LocalDate monday = currentDate.with(DayOfWeek.MONDAY);
                return monday.atStartOfDay().minusWeeks(1);

            case "M": // 月
                // 对齐到最近的完整月周期 (月初开始)
                return now.toLocalDate().withDayOfMonth(1).atStartOfDay().minusMonths(1);

            default:
                // 默认返回当前时间的分钟对齐
                return now.withSecond(0).withNano(0).minusMinutes(1);
        }
    }

}
