package learn.camel.sample.component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.model.RouteDefinition;

public class CustomRouteDefinition extends RouteDefinition {
    
    private static final String ROUTE_CACHE = "ROUTE_CACHE";
    private static final String ROUTE_CACHE_KEY = "ROUTE_CACHE_KEY";
    private Map<String, Cache> cacheByUri = new HashMap<String, Cache>() {
        private static final long serialVersionUID = -1607353690959834086L;
        @Override
        public Cache get(Object uri) {
            if(!containsKey(uri)) {
                put(uri.toString(), new Cache(uri.toString()));
            }
            return super.get(uri);
        }
    };
    
    class Cache extends HashMap<ExchangeCacheEntity, ExchangeCacheEntity> {
        private static final long serialVersionUID = -5855026353296951671L;

        private final String uri;

        public Cache(String uri) {
            this.uri = uri;
        }

        public String getUri() {
            return uri;
        }
    }
    
    @Override
    public CustomRouteDefinition from(String uri) {
        return (CustomRouteDefinition) super.from(uri);
    }

    public CustomRouteDefinition toCached(String uri, CachePolicy cachePolicy) {
        this.process(pushCacheAndCacheKey(uri, cachePolicy))
            .choice()
                .when(isUpdatedFromCache(cachePolicy))
                    .log("Found in cache..")
                    .process(popCacheAndCacheKey(uri))
                .otherwise()
                    .doTry()
                        .log("Not found in cache.. allowing normal process to happen")
                        .to(uri)
                        .log("save response in cache")
                        .process(updateCache(cachePolicy))
                    .doFinally()
                        .process(popCacheAndCacheKey(uri))
                    .endDoTry()
            .end();
        return this;
    }

    private Processor popCacheAndCacheKey(String uri) {
        return exchange -> {
            Stack<Cache> cacheStack = (Stack<Cache>) exchange.getProperty(ROUTE_CACHE);
            if (cacheStack != null && !cacheStack.isEmpty() && cacheStack.peek().getUri().equalsIgnoreCase(uri)) {
                Cache cache = cacheStack.pop();
                Stack<ExchangeCacheEntity> keyStack = (Stack<ExchangeCacheEntity>)exchange.getProperty(ROUTE_CACHE_KEY);
                if (keyStack != null && !keyStack.isEmpty() && cache.containsKey(keyStack.peek())) {
                    keyStack.pop();
                }
            }
        };
    }

    private Processor pushCacheAndCacheKey(String uri, CachePolicy cachePolicy) {
        return exchange -> {
            Stack<Cache> cacheStack = (Stack<Cache>) exchange.getProperty(ROUTE_CACHE);
            if (cacheStack == null) {
                cacheStack = new Stack<>();
                exchange.setProperty(ROUTE_CACHE, cacheStack);
            }
            cacheStack.push(cacheByUri.get(uri));
            
            Stack<ExchangeCacheEntity> keyStack = (Stack<ExchangeCacheEntity>)exchange.getProperty(ROUTE_CACHE_KEY);
            if (keyStack == null) {
                keyStack = new Stack<>();
                exchange.setProperty(ROUTE_CACHE_KEY, keyStack);
            }
            keyStack.push(buildExchangeCacheEntity(exchange, cachePolicy.isBodyInKey(),
                    cachePolicy.getHeadersInKey(), cachePolicy.getPropertiesInKey()));
        };
    }

    private Processor updateCache(CachePolicy cachePolicy) {
        return exchange -> {
            ExchangeCacheEntity key = getCacheKey(exchange);
            cache(exchange).put(key, buildExchangeCacheEntity(exchange,
                    cachePolicy.isCacheBody(), cachePolicy.getHeadersToCache(), cachePolicy.getPropertiesToCache()));
            scheduleCacheCleanup(cachePolicy, key, cache(exchange));
        };
    }

    private void scheduleCacheCleanup(CachePolicy cachePolicy, ExchangeCacheEntity key, Cache cache) {
        new Timer(true).schedule(new TimerTask() {
            @Override
            public void run() {
                cache.remove(key);
            }
        }, cachePolicy.getTimeToLive() * 1000);
    }

    private ExchangeCacheEntity getCacheKey(Exchange exchange) {
        return ((Stack<ExchangeCacheEntity>) exchange.getProperty(ROUTE_CACHE_KEY)).peek();
    }

    private Cache cache(Exchange exchange) {
        return ((Stack<Cache>) exchange.getProperty(ROUTE_CACHE)).peek();
    }

    private Predicate isUpdatedFromCache(CachePolicy cachePolicy) {
        return exchange -> {
            ExchangeCacheEntity entity = cache(exchange).get(getCacheKey(exchange));
            if (entity == null) {
                return false;
            }
            if (cachePolicy.isCacheBody()) {
                exchange.getIn().setBody(entity.getBody());
            }
            for (String header : cachePolicy.getHeadersToCache()) {
                exchange.getIn().setHeader(header, entity.getHeaders().get(header));
            }
            for (String property : cachePolicy.getPropertiesToCache()) {
                exchange.setProperty(property, entity.getProperties().get(property));
            }
            return true;
        };
    }
    
    private ExchangeCacheEntity buildExchangeCacheEntity(Exchange exchange, boolean body, Set<String> headers, Set<String> properties) {
        ExchangeCacheEntity exchangeCacheEntity = new ExchangeCacheEntity();
        if (body && exchange.getIn().getBody() != null) {
            // Just a temporary way to convert to bytes.. actually should read from input steam
            exchangeCacheEntity.setBody(exchange.getIn().getBody(String.class).getBytes());
        }
        for (String header : headers) {
            exchangeCacheEntity.getHeaders().put(header, exchange.getIn().getHeader(header));
        }
        for (String property : properties) {
            exchangeCacheEntity.getProperties().put(property, exchange.getProperty(property));
        }
        return exchangeCacheEntity;
    }
}
