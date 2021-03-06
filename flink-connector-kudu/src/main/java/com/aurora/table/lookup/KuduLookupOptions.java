package com.aurora.table.lookup;

/**
 * @author lj.michale
 * @description
 * @date 2021-07-07
 */
public class KuduLookupOptions {

    private final long cacheMaxSize;

    private final long cacheExpireMs;

    private final int maxRetryTimes;

    public static Builder builder() {
        return new Builder();
    }

    public KuduLookupOptions(long cacheMaxSize, long cacheExpireMs, int maxRetryTimes) {
        this.cacheMaxSize = cacheMaxSize;
        this.cacheExpireMs = cacheExpireMs;
        this.maxRetryTimes = maxRetryTimes;
    }

    public long getCacheMaxSize() {
        return cacheMaxSize;
    }


    public long getCacheExpireMs() {
        return cacheExpireMs;
    }


    public int getMaxRetryTimes() {
        return maxRetryTimes;
    }


    public static final class Builder {
        private long cacheMaxSize;
        private long cacheExpireMs;
        private int maxRetryTimes;

        public static Builder options() {
            return new Builder();
        }

        public Builder withCacheMaxSize(long cacheMaxSize) {
            this.cacheMaxSize = cacheMaxSize;
            return this;
        }

        public Builder withCacheExpireMs(long cacheExpireMs) {
            this.cacheExpireMs = cacheExpireMs;
            return this;
        }

        public Builder withMaxRetryTimes(int maxRetryTimes) {
            this.maxRetryTimes = maxRetryTimes;
            return this;
        }

        public KuduLookupOptions build() {
            return new KuduLookupOptions(cacheMaxSize, cacheExpireMs, maxRetryTimes);
        }
    }
}