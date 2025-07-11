package com.okx.trading.strategy;

import com.okx.trading.model.dto.BacktestResultDTO;
import com.okx.trading.model.dto.TradeRecordDTO;
import com.okx.trading.model.entity.CandlestickEntity;
import com.okx.trading.service.impl.Ta4jBacktestService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Position;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.Bar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.okx.trading.util.BacktestDataGenerator.parseIntervalToMinutes;

/**
 * 回测指标计算器
 * 负责计算各种回测指标，包括收益率、风险指标、交易统计等
 */
@Slf4j
public class BacktestMetricsCalculator {

    private static final Logger log = LoggerFactory.getLogger(BacktestMetricsCalculator.class);

    // 计算结果
    private BacktestResultDTO result;

    // 输入参数
    private final BarSeries series;
    private final TradingRecord tradingRecord;
    private final BigDecimal initialAmount;
    private final String strategyType;
    private final String paramDescription;
    private final BigDecimal feeRatio;
    private final String interval;
    private final List<CandlestickEntity> benchmarkCandlesticks;

    // 中间计算结果
    private List<TradeRecordDTO> tradeRecords;
    private List<ArrayList<BigDecimal>> maxLossAndDrawdownList;
    private List<BigDecimal> fullPeriodStrategyReturns;
    private ArrayList<BigDecimal> dailyPrices;
    private ReturnMetrics returnMetrics;
    private RiskMetrics riskMetrics;
    private TradeStatistics tradeStats;

    /**
     * 构造器 - 在构造时完成所有指标计算
     *
     * @param series           BarSeries对象
     * @param tradingRecord    交易记录
     * @param initialAmount    初始资金
     * @param strategyType     策略类型
     * @param paramDescription 参数描述
     * @param feeRatio         交易手续费率
     */
    public BacktestMetricsCalculator(BarSeries series, TradingRecord tradingRecord, BigDecimal initialAmount, String strategyType,
                                     String paramDescription, BigDecimal feeRatio, String interval, List<CandlestickEntity> benchmarkCandlesticks) {
        this.series = series;
        this.tradingRecord = tradingRecord;
        this.initialAmount = initialAmount;
        this.strategyType = strategyType;
        this.paramDescription = paramDescription;
        this.feeRatio = feeRatio;
        this.interval = interval;
        this.benchmarkCandlesticks = benchmarkCandlesticks;

        // 在构造器中完成所有指标计算
        calculateAllMetrics();
    }

    /**
     * 计算所有回测指标
     */
    private void calculateAllMetrics() {
        try {
            // 如果没有交易，返回简单结果
            if (tradingRecord.getPositionCount() == 0) {
                result = createEmptyResult();
                return;
            }

            // 1. 提取交易明细（包含手续费计算）
            tradeRecords = extractTradeRecords();

            // 2. 计算最大损失和最大回撤
            maxLossAndDrawdownList = calculateMaximumLossAndDrawdown();

            // 3. 计算交易统计指标
            tradeStats = calculateTradeStatistics();

            // 4. 计算收益率相关指标
            returnMetrics = calculateReturnMetrics(tradeStats);

            // 5. 计算风险指标
            riskMetrics = calculateRiskMetrics();

            // 6. 构建最终结果
            result = buildFinalResult();

        } catch (Exception e) {
            log.error("计算回测指标时发生错误: {}", e.getMessage(), e);
            result = createErrorResult(e.getMessage());
        }
    }

    /**
     * 创建空结果（无交易情况）
     */
    private BacktestResultDTO createEmptyResult() {
        BacktestResultDTO result = new BacktestResultDTO();
        result.setSuccess(true);
        result.setInitialAmount(initialAmount);
        result.setFinalAmount(initialAmount);
        result.setTotalProfit(BigDecimal.ZERO);
        result.setTotalReturn(BigDecimal.ZERO);
        result.setNumberOfTrades(0);
        result.setProfitableTrades(0);
        result.setUnprofitableTrades(0);
        result.setWinRate(BigDecimal.ZERO);
        result.setAverageProfit(BigDecimal.ZERO);
        result.setMaxDrawdown(BigDecimal.ZERO);
        result.setSharpeRatio(BigDecimal.ZERO);
        result.setStrategyName(strategyType);
        result.setParameterDescription(paramDescription);
        result.setTrades(new ArrayList<>());
        result.setTotalFee(BigDecimal.ZERO);
        
        // 初始化新增的风险指标为零值
        result.setKurtosis(BigDecimal.ZERO);
        result.setCvar(BigDecimal.ZERO);
        result.setVar95(BigDecimal.ZERO);
        result.setVar99(BigDecimal.ZERO);
        result.setInformationRatio(BigDecimal.ZERO);
        result.setTrackingError(BigDecimal.ZERO);
        result.setSterlingRatio(BigDecimal.ZERO);
        result.setBurkeRatio(BigDecimal.ZERO);
        result.setModifiedSharpeRatio(BigDecimal.ZERO);
        result.setDownsideDeviation(BigDecimal.ZERO);
        result.setUptrendCapture(BigDecimal.ZERO);
        result.setDowntrendCapture(BigDecimal.ZERO);
        result.setMaxDrawdownDuration(BigDecimal.ZERO);
        result.setPainIndex(BigDecimal.ZERO);
        result.setRiskAdjustedReturn(BigDecimal.ZERO);
        result.setComprehensiveScore(BigDecimal.ZERO);
        
        return result;
    }

    /**
     * 创建错误结果
     */
    private BacktestResultDTO createErrorResult(String errorMessage) {
        BacktestResultDTO result = new BacktestResultDTO();
        result.setSuccess(false);
        result.setErrorMessage("计算回测指标时发生错误: " + errorMessage);
        return result;
    }

    /**
     * 从交易记录中提取交易明细（带手续费计算）
     */
    private List<TradeRecordDTO> extractTradeRecords() {
        List<TradeRecordDTO> records = new ArrayList<>();
        int index = 1;
        BigDecimal tradeAmount = initialAmount;

        for (Position position : tradingRecord.getPositions()) {
            if (position.isClosed()) {
                // 获取入场和出场信息
                int entryIndex = position.getEntry().getIndex();
                int exitIndex = position.getExit().getIndex();

                Bar entryBar = series.getBar(entryIndex);
                Bar exitBar = series.getBar(exitIndex);

                ZonedDateTime entryTime = entryBar.getEndTime();
                ZonedDateTime exitTime = exitBar.getEndTime();

                BigDecimal entryPrice = new BigDecimal(entryBar.getClosePrice().doubleValue());
                BigDecimal exitPrice = new BigDecimal(exitBar.getClosePrice().doubleValue());

                // 计算入场手续费
                BigDecimal entryFee = tradeAmount.multiply(feeRatio);

                // 扣除入场手续费后的实际交易金额
                BigDecimal actualTradeAmount = tradeAmount.subtract(entryFee);

                // 交易盈亏百分比
                BigDecimal profitPercentage;

                if (position.getEntry().isBuy()) {
                    // 如果是买入操作，盈亏百分比 = (卖出价 - 买入价) / 买入价
                    profitPercentage = exitPrice.subtract(entryPrice)
                            .divide(entryPrice, 4, RoundingMode.HALF_UP);
                } else {
                    // 如果是卖出操作（做空），盈亏百分比 = (买入价 - 卖出价) / 买入价
                    profitPercentage = entryPrice.subtract(exitPrice)
                            .divide(entryPrice, 4, RoundingMode.HALF_UP);
                }

                // 计算出场金额（包含盈亏）
                BigDecimal exitAmount = actualTradeAmount.add(actualTradeAmount.multiply(profitPercentage));

                // 计算出场手续费
                BigDecimal exitFee = exitAmount.multiply(feeRatio);

                // 扣除出场手续费后的实际出场金额
                BigDecimal actualExitAmount = exitAmount.subtract(exitFee);

                // 总手续费
                BigDecimal totalFee = entryFee.add(exitFee);

                // 实际盈亏（考虑手续费）
                BigDecimal actualProfit = actualExitAmount.subtract(tradeAmount);

                // 创建交易记录DTO
                TradeRecordDTO recordDTO = new TradeRecordDTO();
                recordDTO.setIndex(index++);
                recordDTO.setType(position.getEntry().isBuy() ? "BUY" : "SELL");
                recordDTO.setEntryTime(entryTime.toLocalDateTime());
                recordDTO.setExitTime(exitTime.toLocalDateTime());
                recordDTO.setEntryPrice(entryPrice);
                recordDTO.setExitPrice(exitPrice);
                recordDTO.setEntryAmount(tradeAmount);
                recordDTO.setExitAmount(actualExitAmount);
                recordDTO.setProfit(actualProfit);
                recordDTO.setProfitPercentage(profitPercentage);
                recordDTO.setClosed(true);
                recordDTO.setFee(totalFee);

                records.add(recordDTO);

                // 更新下一次交易的资金（全仓交易）
                tradeAmount = actualExitAmount;
            }
        }

        return records;
    }

    /**
     * 计算最大损失和最大回撤
     */
    private List<ArrayList<BigDecimal>> calculateMaximumLossAndDrawdown() {
        if (series == null || series.getBarCount() == 0 || tradingRecord == null || tradingRecord.getPositionCount() == 0) {
            return Arrays.asList();
        }

        ArrayList<BigDecimal> maxLossList = new ArrayList<>();
        ArrayList<BigDecimal> drawdownList = new ArrayList<>();

        // 遍历每个已关闭的交易
        for (Position position : tradingRecord.getPositions()) {
            BigDecimal maxLoss = BigDecimal.ZERO;
            BigDecimal maxDrawdown = BigDecimal.ZERO;

            if (position.isClosed()) {
                // 获取入场和出场信息
                int entryIndex = position.getEntry().getIndex();
                int exitIndex = position.getExit().getIndex();
                BarSeries subSeries = series.getSubSeries(entryIndex, exitIndex + 1);

                // 获取入场和出场价格
                BigDecimal entryPrice = new BigDecimal(subSeries.getFirstBar().getClosePrice().doubleValue());
                BigDecimal exitPrice = new BigDecimal(subSeries.getLastBar().getClosePrice().doubleValue());

                BigDecimal highestPrice = BigDecimal.ZERO;
                BigDecimal lowestPrice = BigDecimal.valueOf(Long.MAX_VALUE);

                for (int i = 0; i < subSeries.getBarCount(); i++) {
                    BigDecimal closePrice = BigDecimal.valueOf(subSeries.getBar(i).getClosePrice().doubleValue());

                    if (closePrice.compareTo(highestPrice) > 0) {
                        highestPrice = closePrice;
                    }
                    if (closePrice.compareTo(lowestPrice) <= 0) {
                        lowestPrice = closePrice;
                    }

                    BigDecimal lossRate;
                    BigDecimal drawDownRate;

                    if (position.getEntry().isBuy()) {
                        // 如果是买入操作，收益率 = (卖出价 - 买入价) / 买入价
                        lossRate = closePrice.subtract(entryPrice).divide(entryPrice, 8, RoundingMode.HALF_UP);
                        drawDownRate = closePrice.subtract(highestPrice).divide(highestPrice, 8, RoundingMode.HALF_UP);
                    } else {
                        // 如果是卖出操作（做空），收益率 = (买入价 - 卖出价) / 买入价
                        lossRate = closePrice.subtract(exitPrice).divide(entryPrice, 8, RoundingMode.HALF_UP);
                        drawDownRate = closePrice.subtract(lowestPrice).divide(lowestPrice, 8, RoundingMode.HALF_UP);
                    }

                    // 只关注亏损交易
                    if (lossRate.compareTo(BigDecimal.ZERO) < 0) {
                        // 如果当前亏损大于已记录的最大亏损（更负），则更新最大亏损
                        if (lossRate.compareTo(maxLoss) < 0) {
                            maxLoss = lossRate;
                        }
                    }
                    if (drawDownRate.compareTo(BigDecimal.ZERO) < 0) {
                        if (drawDownRate.compareTo(maxDrawdown) < 0) {
                            maxDrawdown = drawDownRate;
                        }
                    }
                }
                maxLossList.add(maxLoss.abs());
                drawdownList.add(maxDrawdown.abs());
            }
        }

        List<ArrayList<BigDecimal>> list = new ArrayList<>();
        list.add(maxLossList);
        list.add(drawdownList);
        return list;
    }

    /**
     * 交易统计指标
     */
    private static class TradeStatistics {
        int tradeCount;
        int profitableTrades;
        BigDecimal totalProfit;
        BigDecimal totalFee;
        BigDecimal finalAmount;
        BigDecimal totalGrossProfit;
        BigDecimal totalGrossLoss;
        BigDecimal profitFactor;
        BigDecimal winRate;
        BigDecimal averageProfit;
        BigDecimal maximumLoss;
        BigDecimal maxDrawdown;
    }

    /**
     * 计算交易统计指标
     */
    private TradeStatistics calculateTradeStatistics() {
        TradeStatistics stats = new TradeStatistics();

        // 设置最大损失和最大回撤到交易记录中
        for (int i = 0; i < tradeRecords.size(); i++) {
            TradeRecordDTO trade = tradeRecords.get(i);
            trade.setMaxLoss(maxLossAndDrawdownList.get(0).get(i));
            trade.setMaxDrowdown(maxLossAndDrawdownList.get(1).get(i));
        }

        stats.tradeCount = tradeRecords.size();
        stats.profitableTrades = 0;
        stats.totalProfit = BigDecimal.ZERO;
        stats.totalFee = BigDecimal.ZERO;
        stats.finalAmount = initialAmount;
        stats.totalGrossProfit = BigDecimal.ZERO;
        stats.totalGrossLoss = BigDecimal.ZERO;

        for (TradeRecordDTO trade : tradeRecords) {
            BigDecimal profit = trade.getProfit();

            if (profit != null) {
                stats.totalProfit = stats.totalProfit.add(profit);

                // 分别累计总盈利和总亏损
                if (profit.compareTo(BigDecimal.ZERO) > 0) {
                    stats.profitableTrades++;
                    stats.totalGrossProfit = stats.totalGrossProfit.add(profit);
                } else {
                    stats.totalGrossLoss = stats.totalGrossLoss.add(profit.abs());
                }
            }

            if (trade.getFee() != null) {
                stats.totalFee = stats.totalFee.add(trade.getFee());
            }
        }

        stats.finalAmount = initialAmount.add(stats.totalProfit);

        // 计算盈利因子 (Profit Factor)
        stats.profitFactor = BigDecimal.ONE;
        if (stats.totalGrossLoss.compareTo(BigDecimal.ZERO) > 0) {
            stats.profitFactor = stats.totalGrossProfit.divide(stats.totalGrossLoss, 4, RoundingMode.HALF_UP);
        } else if (stats.totalGrossProfit.compareTo(BigDecimal.ZERO) > 0) {
            stats.profitFactor = new BigDecimal("999.9999");
        }

        // 计算胜率
        stats.winRate = BigDecimal.ZERO;
        if (stats.tradeCount > 0) {
            stats.winRate = new BigDecimal(stats.profitableTrades).divide(new BigDecimal(stats.tradeCount), 4, RoundingMode.HALF_UP);
        }

        // 计算平均盈利
        stats.averageProfit = BigDecimal.ZERO;
        if (stats.tradeCount > 0) {
            BigDecimal totalReturn = BigDecimal.ZERO;
            if (initialAmount.compareTo(BigDecimal.ZERO) > 0) {
                totalReturn = stats.totalProfit.divide(initialAmount, 4, RoundingMode.HALF_UP);
            }
            stats.averageProfit = totalReturn.divide(new BigDecimal(stats.tradeCount), 4, RoundingMode.HALF_UP);
        }

        // 计算最大损失和最大回撤
        stats.maximumLoss = maxLossAndDrawdownList.get(0).stream().reduce(BigDecimal::max).orElse(BigDecimal.ZERO);
        stats.maxDrawdown = maxLossAndDrawdownList.get(1).stream().reduce(BigDecimal::max).orElse(BigDecimal.ZERO);

        return stats;
    }

    /**
     * 收益率指标
     */
    private static class ReturnMetrics {
        BigDecimal totalReturn;
        BigDecimal annualizedReturn;
    }

    /**
     * 计算收益率相关指标
     */
    private ReturnMetrics calculateReturnMetrics(TradeStatistics stats) {
        ReturnMetrics metrics = new ReturnMetrics();

        // 计算总收益率
        metrics.totalReturn = BigDecimal.ZERO;
        if (initialAmount.compareTo(BigDecimal.ZERO) > 0) {
            metrics.totalReturn = stats.totalProfit.divide(initialAmount, 4, RoundingMode.HALF_UP);
        }

        // 计算年化收益率
        metrics.annualizedReturn = calculateAnnualizedReturn(
                metrics.totalReturn,
                series.getFirstBar().getEndTime().toLocalDateTime(),
                series.getLastBar().getEndTime().toLocalDateTime()
        );

        return metrics;
    }

    /**
     * 风险指标
     */
    private static class RiskMetrics {
        BigDecimal sharpeRatio;
        BigDecimal sortinoRatio;
        BigDecimal calmarRatio;
        BigDecimal omega;
        BigDecimal volatility;
        BigDecimal[] alphaBeta;
        BigDecimal treynorRatio;
        BigDecimal ulcerIndex;
        BigDecimal skewness;
        
        // 新增风险指标
        BigDecimal kurtosis;                    // 峰度 - 衡量收益率分布的尾部风险
        BigDecimal cvar;                        // 条件风险价值 - 极端损失的期望值
        BigDecimal var95;                       // 95%置信度下的风险价值
        BigDecimal var99;                       // 99%置信度下的风险价值
        BigDecimal informationRatio;            // 信息比率 - 超额收益相对于跟踪误差的比率
        BigDecimal trackingError;               // 跟踪误差 - 策略与基准收益率的标准差
        BigDecimal sterlingRatio;               // Sterling比率 - 年化收益与平均最大回撤的比率
        BigDecimal burkeRatio;                  // Burke比率 - 年化收益与平方根回撤的比率
        BigDecimal modifiedSharpeRatio;         // 修正夏普比率 - 考虑偏度和峰度的夏普比率
        BigDecimal downsideDeviation;           // 下行偏差 - 只考虑负收益的标准差
        BigDecimal uptrendCapture;              // 上涨捕获率 - 基准上涨时策略的表现
        BigDecimal downtrendCapture;            // 下跌捕获率 - 基准下跌时策略的表现
        BigDecimal maxDrawdownDuration;         // 最大回撤持续期 - 从峰值到恢复的最长时间
        BigDecimal painIndex;                   // 痛苦指数 - 回撤深度与持续时间的综合指标
        BigDecimal riskAdjustedReturn;          // 风险调整收益 - 综合多种风险因素的收益评估
        
        // 综合评分
        BigDecimal comprehensiveScore;          // 综合评分 (0-10分)
    }

    /**
     * 计算风险指标
     */
    private RiskMetrics calculateRiskMetrics() {
        RiskMetrics metrics = new RiskMetrics();

        BigDecimal riskFreeRate = BigDecimal.valueOf(0);

        // 动态检测年化因子
        int annualizationFactor = detectAnnualizationFactor(series);
        log.info("检测到的年化因子: {}", annualizationFactor);

        // 计算夏普比率和索提诺比例 - 全周期策略收益率（包括未持仓期间的0收益）
        fullPeriodStrategyReturns = calculateFullPeriodStrategyReturns(series, tradingRecord, true);
        metrics.sharpeRatio = Ta4jBacktestService.calculateSharpeRatio(fullPeriodStrategyReturns, riskFreeRate, annualizationFactor);
        metrics.omega = Ta4jBacktestService.calculateOmegaRatio(fullPeriodStrategyReturns, riskFreeRate);

        // 计算Sortino比率
        metrics.sortinoRatio = Ta4jBacktestService.calculateSortinoRatio(fullPeriodStrategyReturns, riskFreeRate, annualizationFactor);

        // 计算所有日期的价格数据用于其他指标计算
        dailyPrices = new ArrayList<>();
        for (int i = 0; i <= series.getEndIndex(); i++) {
            double closePrice = series.getBar(i).getClosePrice().doubleValue();
            dailyPrices.add(BigDecimal.valueOf(closePrice));
        }

        // 计算波动率（基于收盘价）
        metrics.volatility = calculateVolatility(series, annualizationFactor);

        // Alpha 表示策略超额收益，Beta 表示策略相对于基准收益的敏感度（风险）
        metrics.alphaBeta = calculateAlphaBeta(fullPeriodStrategyReturns, benchmarkCandlesticks);

        // 计算 Treynor 比率
        metrics.treynorRatio = Ta4jBacktestService.calculateTreynorRatio(fullPeriodStrategyReturns, riskFreeRate, metrics.alphaBeta[1]);

        // 计算 Ulcer Index
        metrics.ulcerIndex = Ta4jBacktestService.calculateUlcerIndex(dailyPrices);

        // 计算收益率序列的偏度 (Skewness)
        metrics.skewness = Ta4jBacktestService.calculateSkewness(fullPeriodStrategyReturns);

        // 计算Calmar比率
        metrics.calmarRatio = Ta4jBacktestService.calculateCalmarRatio(returnMetrics.annualizedReturn, tradeStats.maxDrawdown.abs());

        // 新增风险指标计算
        
        // 计算峰度 (Kurtosis) - 衡量收益率分布的尾部风险
        metrics.kurtosis = calculateKurtosis(fullPeriodStrategyReturns);
        
        // 计算风险价值 (VaR) 和条件风险价值 (CVaR)
        BigDecimal[] varResults = calculateVaRAndCVaR(fullPeriodStrategyReturns);
        metrics.var95 = varResults[0];  // 95% VaR
        metrics.var99 = varResults[1];  // 99% VaR
        metrics.cvar = varResults[2];   // CVaR (Expected Shortfall)
        
        // 计算下行偏差 (Downside Deviation)
        metrics.downsideDeviation = calculateDownsideDeviation(fullPeriodStrategyReturns, riskFreeRate);
        
        // 计算跟踪误差和信息比率
        List<BigDecimal> benchmarkReturns = calculateBenchmarkReturns();
        metrics.trackingError = calculateTrackingError(fullPeriodStrategyReturns, benchmarkReturns);
        metrics.informationRatio = calculateInformationRatio(fullPeriodStrategyReturns, benchmarkReturns, metrics.trackingError);
        
        // 计算Sterling比率和Burke比率
        metrics.sterlingRatio = calculateSterlingRatio(returnMetrics.annualizedReturn, dailyPrices);
        metrics.burkeRatio = calculateBurkeRatio(returnMetrics.annualizedReturn, dailyPrices);
        
        // 计算修正夏普比率（考虑偏度和峰度）
        metrics.modifiedSharpeRatio = calculateModifiedSharpeRatio(metrics.sharpeRatio, metrics.skewness, metrics.kurtosis);
        
        // 计算上涨和下跌捕获率
        BigDecimal[] captureRatios = calculateCaptureRatios(fullPeriodStrategyReturns, benchmarkReturns);
        metrics.uptrendCapture = captureRatios[0];
        metrics.downtrendCapture = captureRatios[1];
        
        // 计算最大回撤持续期和痛苦指数
        metrics.maxDrawdownDuration = calculateMaxDrawdownDuration(dailyPrices);
        metrics.painIndex = calculatePainIndex(dailyPrices);
        
        // 计算风险调整收益
        metrics.riskAdjustedReturn = calculateRiskAdjustedReturn(returnMetrics.totalReturn, metrics);
        
        // 计算综合评分 (0-10分)
        metrics.comprehensiveScore = calculateComprehensiveScore(returnMetrics, tradeStats, metrics);

        return metrics;
    }

    /**
     * 构建最终结果
     */
    private BacktestResultDTO buildFinalResult() {


        BacktestResultDTO result = new BacktestResultDTO();
        result.setSuccess(true);
        result.setInitialAmount(initialAmount);
        result.setFinalAmount(tradeStats.finalAmount);
        result.setTotalProfit(tradeStats.totalProfit);
        result.setTotalReturn(returnMetrics.totalReturn);
        result.setAnnualizedReturn(returnMetrics.annualizedReturn);
        result.setNumberOfTrades(tradeStats.tradeCount);
        result.setProfitableTrades(tradeStats.profitableTrades);
        result.setUnprofitableTrades(tradeStats.tradeCount - tradeStats.profitableTrades);
        result.setWinRate(tradeStats.winRate);
        result.setAverageProfit(tradeStats.averageProfit);
        result.setMaxDrawdown(tradeStats.maxDrawdown);
        result.setSharpeRatio(riskMetrics.sharpeRatio);
        result.setSortinoRatio(riskMetrics.sortinoRatio);
        result.setCalmarRatio(riskMetrics.calmarRatio);
        result.setOmega(riskMetrics.omega);
        result.setAlpha(riskMetrics.alphaBeta[0]);
        result.setBeta(riskMetrics.alphaBeta[1]);
        result.setTreynorRatio(riskMetrics.treynorRatio);
        result.setUlcerIndex(riskMetrics.ulcerIndex);
        result.setSkewness(riskMetrics.skewness);
        result.setMaximumLoss(tradeStats.maximumLoss);
        result.setVolatility(riskMetrics.volatility);
        result.setProfitFactor(tradeStats.profitFactor);
        result.setStrategyName(strategyType);
        result.setParameterDescription(paramDescription);
        result.setTrades(tradeRecords);
        result.setTotalFee(tradeStats.totalFee);
        
        // 设置新增的风险指标
        result.setKurtosis(riskMetrics.kurtosis);
        result.setCvar(riskMetrics.cvar);
        result.setVar95(riskMetrics.var95);
        result.setVar99(riskMetrics.var99);
        result.setInformationRatio(riskMetrics.informationRatio);
        result.setTrackingError(riskMetrics.trackingError);
        result.setSterlingRatio(riskMetrics.sterlingRatio);
        result.setBurkeRatio(riskMetrics.burkeRatio);
        result.setModifiedSharpeRatio(riskMetrics.modifiedSharpeRatio);
        result.setDownsideDeviation(riskMetrics.downsideDeviation);
        result.setUptrendCapture(riskMetrics.uptrendCapture);
        result.setDowntrendCapture(riskMetrics.downtrendCapture);
        result.setMaxDrawdownDuration(riskMetrics.maxDrawdownDuration);
        result.setPainIndex(riskMetrics.painIndex);
        result.setRiskAdjustedReturn(riskMetrics.riskAdjustedReturn);
        
        // 设置综合评分
        result.setComprehensiveScore(riskMetrics.comprehensiveScore);

        return result;
    }

    /**
     * 计算全周期策略收益率序列
     */
    private List<BigDecimal> calculateFullPeriodStrategyReturns(BarSeries series, TradingRecord tradingRecord, boolean useLogReturn) {
        List<BigDecimal> returns = new ArrayList<>();

        if (series == null || series.getBarCount() < 2) {
            return returns;
        }

        // 如果没有交易记录，整个期间都是0收益
        if (tradingRecord == null || tradingRecord.getPositionCount() == 0) {
            for (int i = 1; i < series.getBarCount(); i++) {
                returns.add(BigDecimal.ZERO);
            }
            return returns;
        }

        // 创建持仓期间标记数组
        boolean[] isInPosition = new boolean[series.getBarCount()];
        boolean[] isEntryDay = new boolean[series.getBarCount()];
        boolean[] isExitDay = new boolean[series.getBarCount()];

        // 标记所有持仓期间、买入日和卖出日
        for (Position position : tradingRecord.getPositions()) {
            if (position.isClosed()) {
                int entryIndex = position.getEntry().getIndex();
                int exitIndex = position.getExit().getIndex();

                // 标记买入日和卖出日
                if (entryIndex < isEntryDay.length) {
                    isEntryDay[entryIndex] = true;
                }
                if (exitIndex < isExitDay.length) {
                    isExitDay[exitIndex] = true;
                }

                // 从入场时间点到出场时间点都标记为持仓状态
                for (int i = entryIndex; i <= exitIndex; i++) {
                    if (i < isInPosition.length) {
                        isInPosition[i] = true;
                    }
                }
            }
        }

        // 计算每个时间点的收益率
        for (int i = 1; i < series.getBarCount(); i++) {
            BigDecimal dailyReturn = BigDecimal.ZERO;

            // 边界条件1：持仓第一天（买入日）收益率为0，因为只是买入，没有收益
            if (isEntryDay[i]) {
                dailyReturn = BigDecimal.ZERO;
            }
            // 边界条件2：卖出日的后一天收益率为0（已经没有持仓）
            else if (i > 0 && isExitDay[i - 1]) {
                dailyReturn = BigDecimal.ZERO;
            }
            // 正常持仓期间：计算价格收益率（排除买入日）
            else if (isInPosition[i] && !isEntryDay[i]) {
                BigDecimal today = BigDecimal.valueOf(series.getBar(i).getClosePrice().doubleValue());
                BigDecimal yesterday = BigDecimal.valueOf(series.getBar(i - 1).getClosePrice().doubleValue());

                if (yesterday.compareTo(BigDecimal.ZERO) > 0) {
                    if (useLogReturn) {
                        double logR = Math.log(today.doubleValue() / yesterday.doubleValue());
                        dailyReturn = BigDecimal.valueOf(logR);
                    } else {
                        BigDecimal change = today.subtract(yesterday).divide(yesterday, 10, RoundingMode.HALF_UP);
                        dailyReturn = change;
                    }
                } else {
                    dailyReturn = BigDecimal.ZERO;
                }
            }
            // 未持仓期间：收益率为0
            else {
                dailyReturn = BigDecimal.ZERO;
            }

            returns.add(dailyReturn);
        }

        return returns;
    }

    /**
     * 动态检测年化因子
     * 根据BarSeries的时间间隔自动检测合适的年化因子
     */
    private int detectAnnualizationFactor(BarSeries series) {
        if (series == null || series.getBarCount() < 2) {
            return 252; // 默认日级别
        }

        try {
            // 获取前两个Bar的时间间隔
            long minutesBetween = parseIntervalToMinutes(interval);

            // 根据时间间隔判断数据周期
            if (minutesBetween <= 1) {
                // 1分钟级别: 1年 = 365天 * 24小时 * 60分钟 = 525,600
                return 525600;
            } else if (minutesBetween <= 5) {
                // 5分钟级别: 525,600 / 5 = 105,120
                return 105120;
            } else if (minutesBetween <= 15) {
                // 15分钟级别: 525,600 / 15 = 35,040
                return 35040;
            } else if (minutesBetween <= 30) {
                // 30分钟级别: 525,600 / 30 = 17,520
                return 17520;
            } else if (minutesBetween <= 60) {
                // 1小时级别: 365天 * 24小时 = 8,760
                return 8760;
            } else if (minutesBetween <= 240) {
                // 4小时级别: 8,760 / 4 = 2,190
                return 2190;
            } else if (minutesBetween <= 360) {
                // 6小时级别: 8,760 / 6 = 1,460
                return 1460;
            } else if (minutesBetween <= 720) {
                // 12小时级别: 8,760 / 12 = 730
                return 730;
            } else if (minutesBetween <= 1440) {
                // 1天级别: 365天
                return 365;
            } else if (minutesBetween <= 10080) {
                // 1周级别: 52周
                return 52;
            } else {
                // 1月级别: 12个月
                return 12;
            }
        } catch (Exception e) {
            log.warn("检测年化因子时出错，使用默认值252: {}", e.getMessage());
            return 252; // 出错时使用默认日级别
        }
    }

    /**
     * 计算波动率（基于收盘价）
     */
    private BigDecimal calculateVolatility(BarSeries series, int annualizationFactor) {
        if (series == null || series.getBarCount() < 2) {
            return BigDecimal.ZERO;
        }

        // 收集所有收盘价
        List<BigDecimal> closePrices = new ArrayList<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            double closePrice = series.getBar(i).getClosePrice().doubleValue();
            closePrices.add(BigDecimal.valueOf(closePrice));
        }

        // 计算收盘价的对数收益率
        List<BigDecimal> logReturns = new ArrayList<>();
        for (int i = 1; i < closePrices.size(); i++) {
            BigDecimal today = closePrices.get(i);
            BigDecimal yesterday = closePrices.get(i - 1);

            if (yesterday.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            double logReturn = Math.log(today.doubleValue() / yesterday.doubleValue());
            logReturns.add(BigDecimal.valueOf(logReturn));
        }

        if (logReturns.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // 计算对数收益率的平均值
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal r : logReturns) {
            sum = sum.add(r);
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(logReturns.size()), 10, RoundingMode.HALF_UP);

        // 计算对数收益率的方差
        BigDecimal sumSquaredDiff = BigDecimal.ZERO;
        for (BigDecimal r : logReturns) {
            BigDecimal diff = r.subtract(mean);
            sumSquaredDiff = sumSquaredDiff.add(diff.multiply(diff));
        }
        BigDecimal variance = sumSquaredDiff.divide(BigDecimal.valueOf(logReturns.size()), 10, RoundingMode.HALF_UP);

        // 计算标准差（波动率）
        BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

        // 年化波动率（使用动态年化因子）
        BigDecimal annualizedVolatility = stdDev.multiply(BigDecimal.valueOf(Math.sqrt(annualizationFactor)));

        return annualizedVolatility.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 计算年化收益率
     */
    private BigDecimal calculateAnnualizedReturn(BigDecimal totalReturn, LocalDateTime startTime, LocalDateTime endTime) {
        if (totalReturn == null || startTime == null || endTime == null || startTime.isAfter(endTime)) {
            log.warn("计算年化收益率的参数无效");
            return BigDecimal.ZERO;
        }

        // 计算回测持续的天数
        long daysBetween = ChronoUnit.DAYS.between(startTime, endTime);

        // 避免除以零错误
        if (daysBetween <= 0) {
            return totalReturn; // 如果时间跨度小于1天，直接返回总收益率
        }

        // 计算年化收益率: (1 + totalReturn)^(365/daysBetween) - 1
        BigDecimal base = BigDecimal.ONE.add(totalReturn);
        BigDecimal exponent = new BigDecimal("365").divide(new BigDecimal(daysBetween), 8, RoundingMode.HALF_UP);

        BigDecimal result;
        try {
            double baseDouble = base.doubleValue();
            double exponentDouble = exponent.doubleValue();
            double power = Math.pow(baseDouble, exponentDouble);

            result = new BigDecimal(power).subtract(BigDecimal.ONE);
        } catch (Exception e) {
            log.error("计算年化收益率时出错", e);
            return BigDecimal.ZERO;
        }

        return result;
    }

    /**
     * 计算 Alpha 和 Beta
     * Alpha 表示策略超额收益，Beta 表示策略相对于基准收益的敏感度（风险）
     *
     * @param strategyReturns  策略每日收益率序列
     * @param benchmarkReturns 基准每日收益率序列
     * @return 包含Alpha和Beta的数组 [Alpha, Beta]
     */
    public static BigDecimal[] calculateAlphaBeta(List<BigDecimal> strategyReturns, List<CandlestickEntity> benchmarkCandlesticks) {

        List<BigDecimal> benchmarkPriceList = benchmarkCandlesticks.stream().map(CandlestickEntity::getClose).collect(Collectors.toList());
        List<BigDecimal> benchmarkReturns = new ArrayList<>();
        benchmarkReturns.add(BigDecimal.ZERO);
        for (int i = 1; i < benchmarkPriceList.size(); i++) {
            // 使用对数收益率保持与策略收益率计算的一致性
            double logReturn = Math.log(benchmarkPriceList.get(i).doubleValue() / benchmarkPriceList.get(i - 1).doubleValue());
            benchmarkReturns.add(BigDecimal.valueOf(logReturn));
        }

        // 添加空值检查和长度验证，避免抛出异常
        if (strategyReturns == null || strategyReturns.isEmpty()) {
            System.out.println("策略收益率序列为空，返回默认Alpha=0, Beta=1");
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ONE};
        }

        if (benchmarkReturns == null || benchmarkReturns.isEmpty()) {
            System.out.println("基准收益率序列为空，返回默认Alpha=0, Beta=1");
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ONE};
        }

        // 如果长度不匹配，取较短的长度，避免抛出异常
        int minLength = Math.min(strategyReturns.size(), benchmarkReturns.size());
        if (minLength == 0) {
            System.out.println("收益率序列长度为0，返回默认Alpha=0, Beta=1");
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ONE};
        }

        // 截取到相同长度，确保不会出现长度不匹配问题
        List<BigDecimal> adjustedStrategyReturns = strategyReturns.subList(0, minLength);
        List<BigDecimal> adjustedBenchmarkReturns = benchmarkReturns.subList(0, minLength);

        System.out.println("计算Alpha/Beta: 策略收益率数量=" + adjustedStrategyReturns.size() + ", 基准收益率数量=" + adjustedBenchmarkReturns.size());

        int n = adjustedStrategyReturns.size();

        // 计算策略和基准的平均收益率
        double meanStrategy = adjustedStrategyReturns.stream().mapToDouble(d -> d.doubleValue()).average().orElse(0.0);
        double meanBenchmark = adjustedBenchmarkReturns.stream().mapToDouble(d -> d.doubleValue()).average().orElse(0.0);

        double covariance = 0.0;        // 协方差 numerator部分
        double varianceBenchmark = 0.0; // 基准收益率的方差 denominator部分

        // 计算协方差和基准方差
        for (int i = 0; i < n; i++) {
            double sDiff = adjustedStrategyReturns.get(i).doubleValue() - meanStrategy;
            double bDiff = adjustedBenchmarkReturns.get(i).doubleValue() - meanBenchmark;

            covariance += sDiff * bDiff;
            varianceBenchmark += bDiff * bDiff;
        }

        covariance /= n;        // 求平均协方差
        varianceBenchmark /= n; // 求平均方差

        // 防止除以0
        double beta = varianceBenchmark == 0 ? 0 : covariance / varianceBenchmark;

        // Alpha = 策略平均收益 - Beta * 基准平均收益
        double alpha = meanStrategy - beta * meanBenchmark;

        return new BigDecimal[]{BigDecimal.valueOf(alpha), BigDecimal.valueOf(beta)};
    }


    /**
     * 获取计算结果
     */
    public BacktestResultDTO getResult() {
        return result;
    }

    // ====================== 新增风险指标计算方法 ======================

    /**
     * 计算峰度 (Kurtosis) - 衡量收益率分布的尾部风险
     * 峰度越大，极端收益出现的概率越高
     */
    private BigDecimal calculateKurtosis(List<BigDecimal> returns) {
        if (returns == null || returns.size() < 4) {
            return BigDecimal.ZERO;
        }

        // 计算均值
        double mean = returns.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
        
        // 计算方差
        double variance = returns.stream()
                .mapToDouble(r -> Math.pow(r.doubleValue() - mean, 2))
                .average().orElse(0.0);
        
        if (variance <= 0) {
            return BigDecimal.ZERO;
        }

        // 计算四阶中心矩
        double fourthMoment = returns.stream()
                .mapToDouble(r -> Math.pow(r.doubleValue() - mean, 4))
                .average().orElse(0.0);

        // 峰度 = 四阶中心矩 / 方差^2 - 3
        double kurtosis = (fourthMoment / Math.pow(variance, 2)) - 3.0;
        
        return BigDecimal.valueOf(kurtosis).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 计算风险价值 (VaR) 和条件风险价值 (CVaR)
     * @return [VaR95%, VaR99%, CVaR]
     */
    private BigDecimal[] calculateVaRAndCVaR(List<BigDecimal> returns) {
        if (returns == null || returns.isEmpty()) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        }

        // 排序收益率（从小到大）
        List<Double> sortedReturns = returns.stream()
                .mapToDouble(BigDecimal::doubleValue)
                .sorted()
                .boxed()
                .collect(Collectors.toList());

        int n = sortedReturns.size();
        
        // 计算VaR (95%和99%置信度)
        int var95Index = (int) Math.ceil(n * 0.05) - 1; // 5%分位数
        int var99Index = (int) Math.ceil(n * 0.01) - 1; // 1%分位数
        
        var95Index = Math.max(0, Math.min(var95Index, n - 1));
        var99Index = Math.max(0, Math.min(var99Index, n - 1));
        
        BigDecimal var95 = BigDecimal.valueOf(-sortedReturns.get(var95Index));
        BigDecimal var99 = BigDecimal.valueOf(-sortedReturns.get(var99Index));

        // 计算CVaR (条件风险价值) - 超过VaR95的损失的平均值
        double cvarSum = 0.0;
        int cvarCount = 0;
        for (int i = 0; i <= var95Index; i++) {
            cvarSum += sortedReturns.get(i);
            cvarCount++;
        }
        
        BigDecimal cvar = BigDecimal.ZERO;
        if (cvarCount > 0) {
            cvar = BigDecimal.valueOf(-cvarSum / cvarCount);
        }

        return new BigDecimal[]{
                var95.setScale(4, RoundingMode.HALF_UP),
                var99.setScale(4, RoundingMode.HALF_UP),
                cvar.setScale(4, RoundingMode.HALF_UP)
        };
    }

    /**
     * 计算下行偏差 (Downside Deviation) - 只考虑负收益的标准差
     */
    private BigDecimal calculateDownsideDeviation(List<BigDecimal> returns, BigDecimal target) {
        if (returns == null || returns.isEmpty()) {
            return BigDecimal.ZERO;
        }

        List<Double> downsideReturns = returns.stream()
                .filter(r -> r.compareTo(target) < 0)
                .mapToDouble(r -> Math.pow(r.subtract(target).doubleValue(), 2))
                .boxed()
                .collect(Collectors.toList());

        if (downsideReturns.isEmpty()) {
            return BigDecimal.ZERO;
        }

        double variance = downsideReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return BigDecimal.valueOf(Math.sqrt(variance)).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 计算基准收益率序列
     */
    private List<BigDecimal> calculateBenchmarkReturns() {
        List<BigDecimal> benchmarkReturns = new ArrayList<>();
        
        if (benchmarkCandlesticks == null || benchmarkCandlesticks.size() < 2) {
            // 如果没有基准数据，返回与策略收益率相同长度的零收益率
            for (int i = 0; i < fullPeriodStrategyReturns.size(); i++) {
                benchmarkReturns.add(BigDecimal.ZERO);
            }
            return benchmarkReturns;
        }

        // 计算基准的对数收益率
        for (int i = 1; i < benchmarkCandlesticks.size(); i++) {
            BigDecimal current = benchmarkCandlesticks.get(i).getClose();
            BigDecimal previous = benchmarkCandlesticks.get(i-1).getClose();
            
            if (previous.compareTo(BigDecimal.ZERO) > 0) {
                double logReturn = Math.log(current.doubleValue() / previous.doubleValue());
                benchmarkReturns.add(BigDecimal.valueOf(logReturn));
            } else {
                benchmarkReturns.add(BigDecimal.ZERO);
            }
        }

        // 确保长度匹配
        while (benchmarkReturns.size() < fullPeriodStrategyReturns.size()) {
            benchmarkReturns.add(BigDecimal.ZERO);
        }
        
        // 截取到相同长度
        if (benchmarkReturns.size() > fullPeriodStrategyReturns.size()) {
            benchmarkReturns = benchmarkReturns.subList(0, fullPeriodStrategyReturns.size());
        }

        return benchmarkReturns;
    }

    /**
     * 计算跟踪误差 (Tracking Error) - 策略与基准收益率差异的标准差
     */
    private BigDecimal calculateTrackingError(List<BigDecimal> strategyReturns, List<BigDecimal> benchmarkReturns) {
        if (strategyReturns == null || benchmarkReturns == null || 
            strategyReturns.size() != benchmarkReturns.size()) {
            return BigDecimal.ZERO;
        }

        List<BigDecimal> trackingDiffs = new ArrayList<>();
        for (int i = 0; i < strategyReturns.size(); i++) {
            BigDecimal diff = strategyReturns.get(i).subtract(benchmarkReturns.get(i));
            trackingDiffs.add(diff);
        }

        // 计算跟踪差异的标准差
        double mean = trackingDiffs.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
        double variance = trackingDiffs.stream()
                .mapToDouble(d -> Math.pow(d.doubleValue() - mean, 2))
                .average().orElse(0.0);

        return BigDecimal.valueOf(Math.sqrt(variance)).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 计算信息比率 (Information Ratio) - 超额收益相对于跟踪误差的比率
     */
    private BigDecimal calculateInformationRatio(List<BigDecimal> strategyReturns, 
                                                List<BigDecimal> benchmarkReturns, 
                                                BigDecimal trackingError) {
        if (trackingError.compareTo(BigDecimal.ZERO) == 0 || 
            strategyReturns == null || benchmarkReturns == null ||
            strategyReturns.size() != benchmarkReturns.size()) {
            return BigDecimal.ZERO;
        }

        // 计算平均超额收益
        double avgExcessReturn = 0.0;
        for (int i = 0; i < strategyReturns.size(); i++) {
            avgExcessReturn += strategyReturns.get(i).subtract(benchmarkReturns.get(i)).doubleValue();
        }
        avgExcessReturn /= strategyReturns.size();

        return BigDecimal.valueOf(avgExcessReturn).divide(trackingError, 4, RoundingMode.HALF_UP);
    }

    /**
     * 计算Sterling比率 - 年化收益与平均最大回撤的比率
     */
    private BigDecimal calculateSterlingRatio(BigDecimal annualizedReturn, List<BigDecimal> prices) {
        if (prices == null || prices.size() < 2) {
            return BigDecimal.ZERO;
        }

        BigDecimal avgMaxDrawdown = calculateAverageMaxDrawdown(prices);
        
        if (avgMaxDrawdown.compareTo(BigDecimal.ZERO) == 0) {
            return annualizedReturn.compareTo(BigDecimal.ZERO) > 0 ? 
                   new BigDecimal("999.9999") : BigDecimal.ZERO;
        }

        return annualizedReturn.divide(avgMaxDrawdown, 4, RoundingMode.HALF_UP);
    }

    /**
     * 计算Burke比率 - 年化收益与平方根回撤的比率
     */
    private BigDecimal calculateBurkeRatio(BigDecimal annualizedReturn, List<BigDecimal> prices) {
        if (prices == null || prices.size() < 2) {
            return BigDecimal.ZERO;
        }

        BigDecimal sqrtDrawdown = calculateSquareRootDrawdown(prices);
        
        if (sqrtDrawdown.compareTo(BigDecimal.ZERO) == 0) {
            return annualizedReturn.compareTo(BigDecimal.ZERO) > 0 ? 
                   new BigDecimal("999.9999") : BigDecimal.ZERO;
        }

        return annualizedReturn.divide(sqrtDrawdown, 4, RoundingMode.HALF_UP);
    }

    /**
     * 计算修正夏普比率 - 考虑偏度和峰度的夏普比率
     */
    private BigDecimal calculateModifiedSharpeRatio(BigDecimal sharpeRatio, BigDecimal skewness, BigDecimal kurtosis) {
        if (sharpeRatio == null) {
            return BigDecimal.ZERO;
        }

        // 修正因子：考虑偏度和峰度的影响
        // 修正夏普比率 = 夏普比率 * (1 + (偏度/6)*夏普比率 + (峰度-3)/24*夏普比率^2)
        BigDecimal sr = sharpeRatio;
        BigDecimal s = skewness != null ? skewness : BigDecimal.ZERO;
        BigDecimal k = kurtosis != null ? kurtosis : BigDecimal.ZERO;

        BigDecimal term1 = s.divide(BigDecimal.valueOf(6), 8, RoundingMode.HALF_UP).multiply(sr);
        BigDecimal term2 = k.subtract(BigDecimal.valueOf(3))
                           .divide(BigDecimal.valueOf(24), 8, RoundingMode.HALF_UP)
                           .multiply(sr.multiply(sr));

        BigDecimal modifier = BigDecimal.ONE.add(term1).subtract(term2);
        
        return sr.multiply(modifier).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 计算上涨和下跌捕获率
     * @return [上涨捕获率, 下跌捕获率]
     */
    private BigDecimal[] calculateCaptureRatios(List<BigDecimal> strategyReturns, List<BigDecimal> benchmarkReturns) {
        if (strategyReturns == null || benchmarkReturns == null || 
            strategyReturns.size() != benchmarkReturns.size()) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        }

        double upStrategySum = 0.0, upBenchmarkSum = 0.0;
        double downStrategySum = 0.0, downBenchmarkSum = 0.0;
        int upCount = 0, downCount = 0;

        for (int i = 0; i < strategyReturns.size(); i++) {
            double strategyReturn = strategyReturns.get(i).doubleValue();
            double benchmarkReturn = benchmarkReturns.get(i).doubleValue();

            if (benchmarkReturn > 0) {
                upStrategySum += strategyReturn;
                upBenchmarkSum += benchmarkReturn;
                upCount++;
            } else if (benchmarkReturn < 0) {
                downStrategySum += strategyReturn;
                downBenchmarkSum += benchmarkReturn;
                downCount++;
            }
        }

        BigDecimal uptrendCapture = BigDecimal.ZERO;
        BigDecimal downtrendCapture = BigDecimal.ZERO;

        if (upCount > 0 && upBenchmarkSum != 0) {
            uptrendCapture = BigDecimal.valueOf(upStrategySum / upBenchmarkSum).setScale(4, RoundingMode.HALF_UP);
        }

        if (downCount > 0 && downBenchmarkSum != 0) {
            downtrendCapture = BigDecimal.valueOf(downStrategySum / downBenchmarkSum).setScale(4, RoundingMode.HALF_UP);
        }

        return new BigDecimal[]{uptrendCapture, downtrendCapture};
    }

    /**
     * 计算最大回撤持续期 - 从峰值到恢复的最长时间（以交易日计算）
     */
    private BigDecimal calculateMaxDrawdownDuration(List<BigDecimal> prices) {
        if (prices == null || prices.size() < 2) {
            return BigDecimal.ZERO;
        }

        int maxDuration = 0;
        int currentDuration = 0;
        BigDecimal peak = prices.get(0);
        boolean inDrawdown = false;

        for (int i = 1; i < prices.size(); i++) {
            BigDecimal currentPrice = prices.get(i);

            if (currentPrice.compareTo(peak) >= 0) {
                // 新高或恢复到峰值
                if (inDrawdown) {
                    maxDuration = Math.max(maxDuration, currentDuration);
                    inDrawdown = false;
                    currentDuration = 0;
                }
                peak = currentPrice;
            } else {
                // 在回撤中
                if (!inDrawdown) {
                    inDrawdown = true;
                    currentDuration = 1;
                } else {
                    currentDuration++;
                }
            }
        }

        // 如果结束时仍在回撤中
        if (inDrawdown) {
            maxDuration = Math.max(maxDuration, currentDuration);
        }

        return BigDecimal.valueOf(maxDuration);
    }

    /**
     * 计算痛苦指数 - 回撤深度与持续时间的综合指标
     */
    private BigDecimal calculatePainIndex(List<BigDecimal> prices) {
        if (prices == null || prices.size() < 2) {
            return BigDecimal.ZERO;
        }

        double totalPain = 0.0;
        BigDecimal peak = prices.get(0);

        for (int i = 1; i < prices.size(); i++) {
            BigDecimal currentPrice = prices.get(i);
            
            if (currentPrice.compareTo(peak) > 0) {
                peak = currentPrice;
            } else {
                // 计算回撤百分比
                BigDecimal drawdown = peak.subtract(currentPrice).divide(peak, 8, RoundingMode.HALF_UP);
                totalPain += drawdown.doubleValue();
            }
        }

        // 平均痛苦指数
        return BigDecimal.valueOf(totalPain / prices.size()).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 计算风险调整收益 - 综合多种风险因素的收益评估
     */
    private BigDecimal calculateRiskAdjustedReturn(BigDecimal totalReturn, RiskMetrics riskMetrics) {
        if (totalReturn == null) {
            return BigDecimal.ZERO;
        }

        // 风险调整收益 = 总收益 / (1 + 综合风险因子)
        // 综合风险因子考虑波动率、最大回撤、下行偏差等
        
        BigDecimal volatilityFactor = riskMetrics.volatility != null ? 
                riskMetrics.volatility.abs() : BigDecimal.ZERO;
        BigDecimal maxDrawdownFactor = tradeStats.maxDrawdown != null ? 
                tradeStats.maxDrawdown.abs() : BigDecimal.ZERO;
        BigDecimal downsideFactor = riskMetrics.downsideDeviation != null ? 
                riskMetrics.downsideDeviation.abs() : BigDecimal.ZERO;

        // 综合风险因子 = 0.4*波动率 + 0.4*最大回撤 + 0.2*下行偏差
        BigDecimal riskFactor = volatilityFactor.multiply(new BigDecimal("0.4"))
                .add(maxDrawdownFactor.multiply(new BigDecimal("0.4")))
                .add(downsideFactor.multiply(new BigDecimal("0.2")));

        BigDecimal denominator = BigDecimal.ONE.add(riskFactor);
        
        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            return totalReturn;
        }

        return totalReturn.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    /**
     * 计算综合评分 (0-10分) - 科学合理的评分体系
     */
    private BigDecimal calculateComprehensiveScore(ReturnMetrics returnMetrics, 
                                                  TradeStatistics tradeStats, 
                                                  RiskMetrics riskMetrics) {
        
        // 评分权重分配 (总计100%)
        // 收益指标: 35%
        // 风险指标: 35%  
        // 交易质量: 20%
        // 稳定性: 10%
        
        double totalScore = 0.0;
        
        // 1. 收益指标评分 (35分) - 年化收益率、总收益率、盈利因子
        double returnScore = calculateReturnScore(returnMetrics, tradeStats) * 0.35;
        
        // 2. 风险指标评分 (35分) - 夏普比率、最大回撤、波动率、VaR等
        double riskScore = calculateRiskScore(riskMetrics, tradeStats) * 0.35;
        
        // 3. 交易质量评分 (20分) - 胜率、交易次数、平均盈利等
        double tradeQualityScore = calculateTradeQualityScore(tradeStats) * 0.20;
        
        // 4. 稳定性评分 (10分) - 偏度、峰度、痛苦指数等
        double stabilityScore = calculateStabilityScore(riskMetrics) * 0.10;
        
        totalScore = returnScore + riskScore + tradeQualityScore + stabilityScore;
        
        // 确保评分在0-10之间
        totalScore = Math.max(0.0, Math.min(10.0, totalScore));
        
        return BigDecimal.valueOf(totalScore).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 计算收益指标评分 (0-10分)
     */
    private double calculateReturnScore(ReturnMetrics returnMetrics, TradeStatistics tradeStats) {
        double score = 0.0;
        
        // 年化收益率评分 (40%) - 20%年化收益率得满分
        if (returnMetrics.annualizedReturn != null) {
            double annualReturn = returnMetrics.annualizedReturn.doubleValue();
            if (annualReturn > 0) {
                score += Math.min(10.0, (annualReturn / 0.20) * 10.0) * 0.4;
            }
        }
        
        // 总收益率评分 (30%) - 50%总收益率得满分
        if (returnMetrics.totalReturn != null) {
            double totalReturn = returnMetrics.totalReturn.doubleValue();
            if (totalReturn > 0) {
                score += Math.min(10.0, (totalReturn / 0.50) * 10.0) * 0.3;
            }
        }
        
        // 盈利因子评分 (30%) - 盈利因子2.0得满分
        if (tradeStats.profitFactor != null) {
            double profitFactor = tradeStats.profitFactor.doubleValue();
            if (profitFactor > 1.0) {
                score += Math.min(10.0, ((profitFactor - 1.0) / 1.0) * 10.0) * 0.3;
            }
        }
        
        return score;
    }

    /**
     * 计算风险指标评分 (0-10分) - 风险越低得分越高
     */
    private double calculateRiskScore(RiskMetrics riskMetrics, TradeStatistics tradeStats) {
        double score = 0.0;
        
        // 夏普比率评分 (30%) - 夏普比率2.0得满分
        if (riskMetrics.sharpeRatio != null) {
            double sharpe = riskMetrics.sharpeRatio.doubleValue();
            if (sharpe > 0) {
                score += Math.min(10.0, (sharpe / 2.0) * 10.0) * 0.3;
            }
        }
        
        // 最大回撤评分 (25%) - 最大回撤越小得分越高，5%以下得满分
        if (tradeStats.maxDrawdown != null) {
            double maxDD = tradeStats.maxDrawdown.abs().doubleValue();
            if (maxDD <= 0.05) {
                score += 10.0 * 0.25;
            } else if (maxDD <= 0.30) {
                score += (1.0 - (maxDD - 0.05) / 0.25) * 10.0 * 0.25;
            }
        }
        
        // Sortino比率评分 (20%) - Sortino比率1.5得满分
        if (riskMetrics.sortinoRatio != null) {
            double sortino = riskMetrics.sortinoRatio.doubleValue();
            if (sortino > 0) {
                score += Math.min(10.0, (sortino / 1.5) * 10.0) * 0.2;
            }
        }
        
        // VaR评分 (15%) - VaR95%在2%以下得满分
        if (riskMetrics.var95 != null) {
            double var95 = riskMetrics.var95.doubleValue();
            if (var95 <= 0.02) {
                score += 10.0 * 0.15;
            } else if (var95 <= 0.10) {
                score += (1.0 - (var95 - 0.02) / 0.08) * 10.0 * 0.15;
            }
        }
        
        // Calmar比率评分 (10%) - Calmar比率1.0得满分
        if (riskMetrics.calmarRatio != null) {
            double calmar = riskMetrics.calmarRatio.doubleValue();
            if (calmar > 0) {
                score += Math.min(10.0, calmar * 10.0) * 0.1;
            }
        }
        
        return score;
    }

    /**
     * 计算交易质量评分 (0-10分)
     */
    private double calculateTradeQualityScore(TradeStatistics tradeStats) {
        double score = 0.0;
        
        // 胜率评分 (40%) - 胜率65%以上得满分
        if (tradeStats.winRate != null) {
            double winRate = tradeStats.winRate.doubleValue();
            if (winRate >= 0.65) {
                score += 10.0 * 0.4;
            } else if (winRate >= 0.30) {
                score += (winRate - 0.30) / 0.35 * 10.0 * 0.4;
            }
        }
        
        // 交易次数评分 (30%) - 10-100次交易为最佳范围
        if (tradeStats.tradeCount >= 10 && tradeStats.tradeCount <= 100) {
            score += 10.0 * 0.3;
        } else if (tradeStats.tradeCount > 100 && tradeStats.tradeCount <= 200) {
            score += (1.0 - (tradeStats.tradeCount - 100) / 100.0) * 10.0 * 0.3;
        } else if (tradeStats.tradeCount >= 5 && tradeStats.tradeCount < 10) {
            score += (tradeStats.tradeCount - 5) / 5.0 * 10.0 * 0.3;
        }
        
        // 平均盈利评分 (30%) - 平均每笔交易盈利2%以上得满分
        if (tradeStats.averageProfit != null && tradeStats.tradeCount > 0) {
            double avgProfit = tradeStats.averageProfit.doubleValue();
            if (avgProfit > 0) {
                score += Math.min(10.0, (avgProfit / 0.02) * 10.0) * 0.3;
            }
        }
        
        return score;
    }

    /**
     * 计算稳定性评分 (0-10分)
     */
    private double calculateStabilityScore(RiskMetrics riskMetrics) {
        double score = 0.0;
        
        // 偏度评分 (40%) - 偏度接近0得分最高
        if (riskMetrics.skewness != null) {
            double skewness = Math.abs(riskMetrics.skewness.doubleValue());
            if (skewness <= 0.5) {
                score += (1.0 - skewness / 0.5) * 10.0 * 0.4;
            }
        }
        
        // 峰度评分 (30%) - 峰度接近0得分最高
        if (riskMetrics.kurtosis != null) {
            double kurtosis = Math.abs(riskMetrics.kurtosis.doubleValue());
            if (kurtosis <= 2.0) {
                score += (1.0 - kurtosis / 2.0) * 10.0 * 0.3;
            }
        }
        
        // 痛苦指数评分 (30%) - 痛苦指数越低得分越高
        if (riskMetrics.painIndex != null) {
            double painIndex = riskMetrics.painIndex.doubleValue();
            if (painIndex <= 0.01) {
                score += 10.0 * 0.3;
            } else if (painIndex <= 0.05) {
                score += (1.0 - (painIndex - 0.01) / 0.04) * 10.0 * 0.3;
            }
        }
        
        return score;
    }

    // ====================== 辅助计算方法 ======================

    /**
     * 计算平均最大回撤
     */
    private BigDecimal calculateAverageMaxDrawdown(List<BigDecimal> prices) {
        if (prices == null || prices.size() < 2) {
            return BigDecimal.ZERO;
        }

        List<BigDecimal> drawdowns = new ArrayList<>();
        BigDecimal peak = prices.get(0);

        for (int i = 1; i < prices.size(); i++) {
            BigDecimal currentPrice = prices.get(i);
            
            if (currentPrice.compareTo(peak) > 0) {
                peak = currentPrice;
            } else {
                BigDecimal drawdown = peak.subtract(currentPrice).divide(peak, 8, RoundingMode.HALF_UP);
                drawdowns.add(drawdown);
            }
        }

        if (drawdowns.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal sum = drawdowns.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(drawdowns.size()), 4, RoundingMode.HALF_UP);
    }

    /**
     * 计算平方根回撤
     */
    private BigDecimal calculateSquareRootDrawdown(List<BigDecimal> prices) {
        if (prices == null || prices.size() < 2) {
            return BigDecimal.ZERO;
        }

        double sumSquaredDrawdowns = 0.0;
        int drawdownCount = 0;
        BigDecimal peak = prices.get(0);

        for (int i = 1; i < prices.size(); i++) {
            BigDecimal currentPrice = prices.get(i);
            
            if (currentPrice.compareTo(peak) > 0) {
                peak = currentPrice;
            } else {
                BigDecimal drawdown = peak.subtract(currentPrice).divide(peak, 8, RoundingMode.HALF_UP);
                sumSquaredDrawdowns += Math.pow(drawdown.doubleValue(), 2);
                drawdownCount++;
            }
        }

        if (drawdownCount == 0) {
            return BigDecimal.ZERO;
        }

        double avgSquaredDrawdown = sumSquaredDrawdowns / drawdownCount;
        return BigDecimal.valueOf(Math.sqrt(avgSquaredDrawdown)).setScale(4, RoundingMode.HALF_UP);
    }
}
