<%#
 Copyright 2013-2017 the original author or authors from the JHipster project.

 This file is part of the JHipster project, see https://jhipster.github.io/
 for more information.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-%>
package <%=packageName%>.gateway.ratelimiting;

import <%=packageName%>.security.SecurityUtils;

import java.time.Duration;
import java.util.function.Supplier;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.spi.CachingProvider;
import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.context.RequestContext;

import io.github.bucket4j.*;
import io.github.bucket4j.grid.GridBucketState;
import io.github.bucket4j.grid.ProxyManager;
import io.github.bucket4j.grid.jcache.JCache;
import <%=packageName%>.config.JHipsterProperties;

/**
 * Zuul filter for limiting the number of HTTP calls per client.
 *
 * See the Bucket4j documentation at https://github.com/vladimir-bukhtoyarov/bucket4j
 * https://github.com/vladimir-bukhtoyarov/bucket4j/blob/master/doc-pages/jcache-usage
 * .md#example-1---limiting-access-to-http-server-by-ip-address
 */
public class RateLimitingFilter extends ZuulFilter {

    private final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    public final static String GATEWAY_RATE_LIMITING_CACHE_NAME = "gateway-rate-limiting";

    private final JHipsterProperties jHipsterTestProperties;

    private javax.cache.Cache<String, GridBucketState> cache;

    private ProxyManager<String> buckets;

    public RateLimitingFilter(JHipsterProperties jHipsterTestProperties) {
        this.jHipsterTestProperties = jHipsterTestProperties;

        CachingProvider cachingProvider = Caching.getCachingProvider();
        CacheManager cacheManager = cachingProvider.getCacheManager();
        CompleteConfiguration<String, GridBucketState> config =
            new MutableConfiguration<String, GridBucketState>()
                .setTypes(String.class, GridBucketState.class);

        this.cache = cacheManager.createCache(GATEWAY_RATE_LIMITING_CACHE_NAME, config);
        this.buckets = Bucket4j.extension(JCache.class).proxyManagerForCache(cache);
    }

    @Override
    public String filterType() {
        return "pre";
    }

    @Override
    public int filterOrder() {
        return 10;
    }

    @Override
    public boolean shouldFilter() {
        // specific APIs can be filtered out using
        // if (RequestContext.getCurrentContext().getRequest().getRequestURI().startsWith("/api")) { ... }
        return true;
    }

    @Override
    public Object run() {
        String bucketId = getId(RequestContext.getCurrentContext().getRequest());
        Bucket bucket = buckets.getProxy(bucketId, getConfigSupplier());
        if (bucket.tryConsume(1)) {
            // the limit is not exceeded
            log.debug("API rate limit OK for {}", bucketId);
        } else {
            // limit is exceeded
            log.info("API rate limit exceeded for {}", bucketId);
            apiLimitExceeded();
        }
        return null;
    }

    private Supplier<BucketConfiguration> getConfigSupplier() {
        return () -> {
            JHipsterProperties.Gateway.RateLimiting rateLimitingProperties =
                jHipsterTestProperties.getGateway().getRateLimiting();

            return Bucket4j.configurationBuilder()
                .addLimit(Bandwidth.simple(rateLimitingProperties.getLimit(),
                    Duration.ofSeconds(rateLimitingProperties.getDurationInSeconds())))
                .buildConfiguration();
        };
    }

    /**
     * Create a Zuul response error when the API limit is exceeded.
     */
    private void apiLimitExceeded() {
        RequestContext ctx = RequestContext.getCurrentContext();
        ctx.setResponseStatusCode(HttpStatus.TOO_MANY_REQUESTS.value());
        if (ctx.getResponseBody() == null) {
            ctx.setResponseBody("API rate limit exceeded");
            ctx.setSendZuulResponse(false);
        }
    }

    /**
     * The ID that will identify the limit: the user login or the user IP address.
     */
    private String getId(HttpServletRequest httpServletRequest) {
        String login = SecurityUtils.getCurrentUserLogin();
        if (login != null) {
            return login;
        } else {
            return httpServletRequest.getRemoteAddr();
        }
    }
}
