package nl.komtek.gpi.services;

import com.cf.PriceDataAPIClient;
import com.cf.client.poloniex.PoloniexPublicAPIClient;
import com.cf.client.poloniex.PoloniexTradingAPIClient;
import com.cf.data.model.poloniex.PoloniexCompleteBalance;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import nl.komtek.gpi.utils.ProxyHandledException;
import nl.komtek.gpi.utils.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Created by Elroy on 20-6-2017.
 */
@Component
public class GunbotProxyService {

	private Logger logger = LogManager.getLogger(GunbotProxyService.class);
	@Autowired
	private ApplicationContext applicationContext;
	@Autowired
	private Util util;
	@Value("${connection.maxRetries:6}")
	private int maxRetries;
	private Map<String, PoloniexTradingAPIClient> poloniexDefaultAPIClients = new HashMap<>();
	private Map<String, PoloniexTradingAPIClient> poloniexTradingAPIClients = new HashMap<>();
	private Map<String, String> marketMapping = new HashMap<>();
	private Map<String, PoloniexTradingAPIClient> poloniexMultiMarketTradingAPIClients = new HashMap<>();
	private PriceDataAPIClient publicClient;
	private RetryPolicy retryPolicy;

	public GunbotProxyService() {
		publicClient = new PoloniexPublicAPIClient();
	}

	@PostConstruct
	private void initService() {

		if (!createMarketMapping()) {
			String apiKey = util.getEnvProperty("default_apiKey");
			String apiSecret = util.getEnvProperty("default_apiSecret");
			poloniexDefaultAPIClients.put("default", new PoloniexTradingAPIClient(apiKey, apiSecret));
			createDefaultTradingClients();

		} else {
			if (!createMarketDefaultApiClients("BTC")) {
				return;
			}

			if (!createMarketDefaultApiClients("ETH")) {
				return;
			}
			if (!createMarketDefaultApiClients("XMR")) {
				return;
			}
			if (!createMarketDefaultApiClients("USDT")) {
				return;
			}
		}

		publicClient = new PoloniexPublicAPIClient();

		retryPolicy = new RetryPolicy()
				.retryOn(failure -> failure instanceof Exception)
				.withDelay(500, TimeUnit.MILLISECONDS)
				.withMaxRetries(maxRetries);
	}

	private boolean createMarketDefaultApiClients(String market) {
		String apiKey = util.getEnvProperty("default_" + market + "_apiKey");
		String apiSecret = util.getEnvProperty("default_" + market + "_apiSecret");
		if (!StringUtils.isEmpty(apiKey) && !StringUtils.isEmpty(apiSecret)) {
			if (!marketMapping.values().contains(market)) {
				logger.error(String.format("Please setup the %s market correctly", market));
				SpringApplication.exit(applicationContext);
				return false;
			} else {
				poloniexDefaultAPIClients.put(market, new PoloniexTradingAPIClient(apiKey, apiSecret));
			}
		}
		return true;
	}

	private boolean createMarketMapping() {
		String[] markets = {"BTC", "ETH", "XMR", "USDT"};

		for (String market : markets) {
			for (int i = 1; i <= 10; i++) {
				String apiKey = util.getEnvProperty(String.format("%s_apiKey%d", market, i));
				String apiSecret = util.getEnvProperty(String.format("%s_apiSecret%d", market, i));
				if (StringUtils.isEmpty(apiKey) || StringUtils.isEmpty(apiSecret)) {
					break;
				}
				marketMapping.put(apiKey, market);
				poloniexMultiMarketTradingAPIClients.put(apiKey, new PoloniexTradingAPIClient(apiKey, apiSecret));
			}
		}
		return marketMapping.size() > 0 && marketMapping.size() == poloniexMultiMarketTradingAPIClients.size();
	}

	private void createDefaultTradingClients() {
		for (int i = 1; i <= 10; i++) {
			String apiKey = util.getEnvProperty(String.format("apiKey%d", i));
			String apiSecret = util.getEnvProperty(String.format("apiSecret%d", i));
			if (apiKey == null || apiSecret == null) {
				break;
			}
			poloniexTradingAPIClients.put(apiKey, new PoloniexTradingAPIClient(apiKey, apiSecret));
		}
	}

	//@Cacheable(value = "BBChartData", key = "#currencyPair")
	public String getBBChartData(String currencyPair, long start, long end, long period) {

		logger.debug("BB ChartData: " + currencyPair + " -- start:" + start + " -- period:" + period);

		while (true) {
			try {
				String result = publicClient.getChartData(currencyPair, period, start, end);
				return analyzeResult(result);
			} catch (Exception e) {
				handleException(e);
			}
		}
	}

	@Cacheable(value = "chartData", key = "#currencyPair+#period")
	public String getChartData(String currencyPair, long period) {

		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.DATE, -2);
		long start = cal.getTimeInMillis() / 1000;

		while (true) {
			try {
				String result = publicClient.getChartData(currencyPair, period, start);
				result = analyzeResult(result);
				logger.debug("chartData: " + currencyPair + " -- start:" + start + " -- period:" + period + " -- " + result);
				return result;
			} catch (Exception e) {
				handleException(e);
			}
		}
	}

	@Cacheable("ticker")
	public String getTicker() {
		return getTickerScheduled();
	}

	@CachePut("ticker")
	public String getTickerScheduled() {
		String result = Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(publicClient.returnTicker()));

		logger.debug("ticker: " + result);

		return result;
	}

	@Cacheable(value = "orderBook", key = "#currencyPair")
	public String getOrderBook(String currencyPair) {
		return getOrderBookScheduled(currencyPair);
	}

	public String get24hVolume() {
		return publicClient.return24Volume();
	}

	@CachePut(value = "orderBook", key = "#currencyPair")
	public String getOrderBookScheduled(String currencyPair) {
		String result = Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(publicClient.returnOrderBook(currencyPair)));

		logger.debug("OrderBook: " + result);

		return result;
	}

	@Cacheable(value = "publicTradeHistory", key = "#currencyPair", sync = true)
	public String getPublicTradeHistory(String currencyPair, long start, long end) {
		return getPublicTradeHistoryScheduled(currencyPair, start, end);
	}

	@CachePut(value = "publicTradeHistory", key = "#currencyPair")
	public String getPublicTradeHistoryScheduled(String currencyPair, long start, long end) {
		String result = Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(publicClient.returnTradeHistory(currencyPair, start, end)));
		logger.debug(currencyPair + "-" + "public trade history: " + result);
		return result;
	}

	@Cacheable(value = "publicTradeHistory", key = "#currencyPair", sync = true)
	public String getPublicTradeHistory(String currencyPair) {
		return getPublicTradeHistoryScheduled(currencyPair);
	}

	@CachePut(value = "publicTradeHistory", key = "#currencyPair")
	public String getPublicTradeHistoryScheduled(String currencyPair) {
		String result = Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(publicClient.returnTradeHistory(currencyPair)));
		logger.debug(currencyPair + "-" + "public trade history: " + result);
		return result;
	}

	@Cacheable(value = "tradeHistory", key = "#market", sync = true)
	public String getTradeHistory(String market) {
		return getTradeHistoryScheduled(market);
	}

	@CachePut(value = "tradeHistory", key = "#market")
	public String getTradeHistoryScheduled(String market) {
		PoloniexTradingAPIClient tradingAPIClient = getMarketDefaultTradingClient(market);
		String result = Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(tradingAPIClient.returnTradeHistory("ALL")));
		logger.debug(market + "-" + "trade history: " + result);
		return result;
	}

	@Cacheable(value = "completeBalances", key = "#market", sync = true)
	public String getCompleteBalances(String market) {
		String result = getCompleteBalancesScheduled(market);
		return hideDust(result);
	}

	@CachePut(value = "completeBalances", key = "#market")
	public String getCompleteBalancesScheduled(String market) {
		PoloniexTradingAPIClient tradingAPIClient = getMarketDefaultTradingClient(market);
		String result = Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(tradingAPIClient.returnCompleteBalances()));
		logger.debug(market + "-" + "complete balances: " + result);
		return result;
	}

	@Cacheable(value = "balances", key = "#market")
	public double getBTCBalance(String market) {
		return getBalancesScheduled(market);

	}

	@CachePut(value = "balances", key = "#market")
	public double getBalancesScheduled(String market) {
		PoloniexTradingAPIClient tradingAPIClient = getMarketDefaultTradingClient(market);
		String result = Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(tradingAPIClient.returnBalances()));
		logger.debug("balances" + result);
		JsonElement jsonElement = new JsonParser().parse(result);
		JsonObject jsonObject = jsonElement.getAsJsonObject();
		return jsonObject.get("BTC").getAsDouble();
	}

	@Cacheable(value = "openOrders", key = "#market", sync = true)
	public String getOpenOrders(String market) {
		return getOpenOrdersScheduled(market);
	}

	@CachePut(value = "openOrders", key = "#market")
	public String getOpenOrdersScheduled(String market) {
		PoloniexTradingAPIClient tradingAPIClient = getMarketDefaultTradingClient(market);
		String result = Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(tradingAPIClient.returnOpenOrders("ALL")));
		logger.debug(market + "-" + "open orders: " + result);
		return result;
	}

	@Caching(evict = {@CacheEvict(value = "openOrders", allEntries = true),
			@CacheEvict(value = "completeBalances", allEntries = true)})
	public String cancelOrder(String key, String orderNumber) {
		logger.debug("Canceling an order");
		PoloniexTradingAPIClient tmpTradingAPIClient;
		if (isUsingMultipleMarkets()) {
			tmpTradingAPIClient = getMultiMarketTradingClient(key);
		} else {
			tmpTradingAPIClient = getTradingClient("random");
		}
		final PoloniexTradingAPIClient tradingAPIClient = tmpTradingAPIClient;
		String result = Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(tradingAPIClient.cancelOrder(orderNumber)));

		return result;
	}

	@Caching(evict = {@CacheEvict(value = "openOrders", allEntries = true),
			@CacheEvict(value = "completeBalances", allEntries = true)})
	public synchronized String buyOrder(String key, String currencyPair, BigDecimal buyPrice, BigDecimal amount) {
		return buyOrderWithProtection(key, currencyPair, buyPrice, amount);
	}

	@Cacheable(value = "buyOrderProtection", key = "#currencyPair")
	@Caching(evict = {@CacheEvict(value = "openOrders", allEntries = true),
			@CacheEvict(value = "completeBalances", allEntries = true)})
	public synchronized String buyOrderWithProtection(String key, String currencyPair, BigDecimal buyPrice, BigDecimal amount) {
		boolean testMode = Boolean.parseBoolean(util.getConfigurationProperty("testMode"));
		boolean globalSellOnlyMode = Boolean.parseBoolean(util.getConfigurationProperty("sellOnlyMode"));
		boolean pairSellOnlyMode = Boolean.parseBoolean(util.getConfigurationProperty(String.format("%s_sellOnlyMode", currencyPair)));

		if (testMode) {
			logger.info("Test mode: We bought {}", currencyPair);
			return "";
		}

		if (globalSellOnlyMode || pairSellOnlyMode) {
			JsonObject jsonObject = new JsonObject();
			String message = String.format("You are not allowed to buy. Sell Only mode is active for %s", currencyPair);
			jsonObject.addProperty("error", message);
			logger.info(jsonObject.toString());
			return jsonObject.toString();
		}

		PoloniexTradingAPIClient tmpTradingAPIClient;
		if (isUsingMultipleMarkets()) {
			tmpTradingAPIClient = getMultiMarketTradingClient(key);
		} else {
			tmpTradingAPIClient = getTradingClient("random");
		}
		final PoloniexTradingAPIClient tradingAPIClient = tmpTradingAPIClient;
		final Boolean fillOrKill = Boolean.valueOf(util.getConfigurationProperty("buy_fillOrKill", "false"));
		final Boolean immediateOrCancel = Boolean.valueOf(util.getConfigurationProperty("buy_immediateOrCancel", "false"));
		String result = Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(tradingAPIClient.buy(currencyPair, buyPrice, amount, fillOrKill, immediateOrCancel, false)));
		logger.info(String.format("Buy order for %s -- %s", currencyPair, result));
		return result;
	}

	@Caching(evict = {@CacheEvict(value = "openOrders", allEntries = true),
			@CacheEvict(value = "completeBalances", allEntries = true)})
	public synchronized String sellOrder(String key, String currencyPair, BigDecimal sellPrice, BigDecimal amount) {
		boolean testMode = Boolean.parseBoolean(util.getConfigurationProperty("testMode"));
		if (testMode) {
			logger.info("Test mode: We sold {}", currencyPair);
			return "";
		}
		PoloniexTradingAPIClient tmpTradingAPIClient;
		if (isUsingMultipleMarkets()) {
			tmpTradingAPIClient = getMultiMarketTradingClient(key);
		} else {
			tmpTradingAPIClient = getTradingClient("random");
		}
		final PoloniexTradingAPIClient tradingAPIClient = tmpTradingAPIClient;
		final Boolean fillOrKill = Boolean.valueOf(util.getConfigurationProperty("sell_fillOrKill", "false"));
		final Boolean immediateOrCancel = Boolean.valueOf(util.getConfigurationProperty("sell_immediateOrCancel", "false"));

		String result = Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(tradingAPIClient.sell(currencyPair, sellPrice, amount, fillOrKill, immediateOrCancel, false)));
		logger.info(String.format("Sell order for %s -- %s", currencyPair, result));
		return result;
	}

	private PoloniexTradingAPIClient getTradingClient(String currencyPair) {
		String apiKey = util.getEnvProperty(currencyPair + "_apiKey");
		if (apiKey != null) {
			return poloniexTradingAPIClients.get(apiKey);
		} else {
			int random = (int) (Math.random() * 1) + poloniexTradingAPIClients.values().size() - 1;
			return (PoloniexTradingAPIClient) poloniexTradingAPIClients.values().toArray()[random];
		}

	}

	private PoloniexTradingAPIClient getMultiMarketTradingClient(String apiKey) {
		return poloniexMultiMarketTradingAPIClients.get(apiKey);
	}

	private PoloniexTradingAPIClient getMarketDefaultTradingClient(String market) {

		PoloniexTradingAPIClient tradingAPIClient = poloniexDefaultAPIClients.get(market);
		if (tradingAPIClient == null) {
			tradingAPIClient = poloniexDefaultAPIClients.get("default");
		}
		return tradingAPIClient;
	}

	public String analyzeResult(String result) {
		if (result == null) {
			throw new ProxyHandledException("No value was returned");
		} else if (result.contains("Nonce")) {
			throw new ProxyHandledException("nonce error: " + result);
		} else if (result.contains("Invalid API")) {
			throw new ProxyHandledException(result);
		} else if (result.contains("Connection timed out")) {
			throw new ProxyHandledException(result);
		}
		return result;
	}

	private void handleException(Throwable e) {
		if (e instanceof ProxyHandledException) {
			logger.debug(e.toString());
		} else if (e instanceof NullPointerException) {
			logger.error("Something is really wrong", e);
		} else {
			logger.error(e);
		}
	}

	public boolean isActiveMarket(String market) {
		return poloniexDefaultAPIClients.get(market) != null;
	}

	public boolean isUsingMultipleMarkets() {
		if (poloniexDefaultAPIClients.get("ETH") != null) {
			return true;
		}
		if (poloniexDefaultAPIClients.get("XMR") != null) {
			return true;
		}
		if (poloniexDefaultAPIClients.get("USDT") != null) {
			return true;
		}
		return false;
	}

	public String getMarket(String apiKey) {
		String market = marketMapping.get(apiKey);
		if (StringUtils.isEmpty(market)) {
			throw new RuntimeException("It seems u are using multiple markets but have not setup this apiKey " + apiKey);
		}
		return market;
	}

	public String checkDefaultKey(String market) {
		PoloniexTradingAPIClient tradingAPIClient = getMarketDefaultTradingClient(market);
		return Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(tradingAPIClient.returnOpenOrders("ALL")));
	}

	public String checkTradingKey(String apiKey) {
		PoloniexTradingAPIClient tradingAPIClient = poloniexTradingAPIClients.get(apiKey);
		return Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(tradingAPIClient.returnOpenOrders("ALL")));
	}

	public String checkMultiMarketTradingKey(String apiKey) {
		PoloniexTradingAPIClient tradingAPIClient = poloniexMultiMarketTradingAPIClients.get(apiKey);
		return Failsafe.with(retryPolicy)
				.onFailedAttempt(this::handleException)
				.get(() -> analyzeResult(tradingAPIClient.returnOpenOrders("ALL")));
	}

	private String hideDust(String result) {
		boolean hidedust = Boolean.parseBoolean(util.getConfigurationProperty("hideDust"));
		if (!hidedust) {
			return result;
		}
		JsonParser jsonParser = new JsonParser();
		JsonElement jElement = jsonParser.parse(result);
		JsonObject jObject = jElement.getAsJsonObject();
		JsonObject filteredObject = new JsonObject();
		for (Map.Entry entry : jObject.entrySet()) {
			JsonElement element = (JsonElement) entry.getValue();
			BigDecimal available = BigDecimal.valueOf(element.getAsJsonObject().get("available").getAsDouble());
			BigDecimal onOrders = BigDecimal.valueOf(element.getAsJsonObject().get("onOrders").getAsDouble());
			BigDecimal btcValue = BigDecimal.valueOf(element.getAsJsonObject().get("btcValue").getAsDouble());

			if (available.doubleValue() == 0) {
				filteredObject.add(entry.getKey().toString(), element);
			}

			double approximatePrice = btcValue.doubleValue() / available.add(onOrders).doubleValue();
			double availableValue = available.doubleValue() * approximatePrice;
			if (!entry.getKey().equals("USDT") && availableValue < 0.00015) {
				available = BigDecimal.ZERO;
			} else if (entry.getKey().equals("USDT") && available.doubleValue() < 1) {
				available = BigDecimal.ZERO;
			}
			PoloniexCompleteBalance balance = new PoloniexCompleteBalance(available, onOrders, btcValue);
			filteredObject.add(entry.getKey().toString(), jsonParser.parse(balance.toString()));
		}
		return filteredObject.toString();
	}
}
