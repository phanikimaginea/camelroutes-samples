package learn.camel.sample;

import java.util.Set;
import java.util.UUID;

import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;

import learn.camel.sample.CacheManager.Cache;

public class CachableRouteDefinition extends RouteDefinition {
    
    private CachePolicy cachePolicy;
    private Cache cache;
    private final String cacheSourceId = "cache-source-" + UUID.randomUUID();

    public RouteDefinition from(String uri, CachePolicy cachePolicy) {
        this.cachePolicy = cachePolicy;
        cache = CacheManager.instance().get(uri);
        return (CachableRouteDefinition) super.from(uri);
    }

    public CachePolicy getCachePolicy() {
        return cachePolicy;
    }

    public Processor updateCache() {
        return exchange -> {
            CacheEntity key = getCacheKey(exchange);
            cache.put(key, buildExchangeCacheEntity(exchange,
                    cachePolicy.isCacheBody(), cachePolicy.getHeadersToCache(), cachePolicy.getPropertiesToCache()), cachePolicy.getTimeToLive());
        };
    }
    
    RouteDefinition makeRouteCacheCapable() {
        RouteDefinition cacheSourceRoute = buildCacheSourceRoute();
        clearOutput();
        this.choice()
                .when(isUpdatedFromCache())
                    .log("Serving from cache")
                .otherwise()
                    .log("Not found in cache. Processing !!!")
                    .to(cacheSourceEndpointUri())
                    .process(updateCache())
                .end()
                .process(exchange -> exchange.removeProperty(cacheKeyProperty()));
        return cacheSourceRoute;
    }

    private CacheEntity getCacheKey(Exchange exchange) {
        return exchange.getProperty(cacheKeyProperty(), CacheEntity.class);
    }
    
    private Predicate isUpdatedFromCache() {
        return exchange -> {
            CacheEntity key = buildExchangeCacheEntity(exchange, cachePolicy.isBodyInKey(),
                    cachePolicy.getHeadersInKey(), cachePolicy.getPropertiesInKey());
            CacheEntity entity = cache.get(key);
            if (entity == null) {
                exchange.setProperty(cacheKeyProperty(), key);
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

    private CacheEntity buildExchangeCacheEntity(Exchange exchange, boolean body, Set<String> headers, Set<String> properties) {
        CacheEntity cacheEntity = new CacheEntity();
        if (body && exchange.getIn().getBody() != null) {
            // Just a temporary way to convert to bytes.. actually should read from input steam
            cacheEntity.setBody(exchange.getIn().getBody(String.class).getBytes());
        }
        for (String header : headers) {
            cacheEntity.getHeaders().put(header, exchange.getIn().getHeader(header));
        }
        for (String property : properties) {
            cacheEntity.getProperties().put(property, exchange.getProperty(property));
        }
        return cacheEntity;
    }

    private RouteDefinition buildCacheSourceRoute() {
        if(cachePolicy == null) {
            throw new IllegalArgumentException("Cache source routes can only be build for routes with cache policy");
        }
        RouteDefinition cacheSourceRoute =
                new RouteDefinition(cacheSourceEndpointUri()).id(cacheSourceId).description("cache source route for " + this);
        for (ProcessorDefinition<?> output : getOutputs()) {
            cacheSourceRoute.addOutput(output);
        }
        return cacheSourceRoute;
    }
    
    private String cacheSourceEndpointUri() {
        return "direct:" + cacheSourceId;
    }
    
    private String cacheKeyProperty() {
        return "KEY#" + cacheSourceId;
    }
}
