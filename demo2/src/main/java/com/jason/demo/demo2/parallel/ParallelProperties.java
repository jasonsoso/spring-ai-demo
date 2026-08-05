package com.jason.demo.demo2.parallel;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "demo.parallel")
public class ParallelProperties {

    private Duration timeout = Duration.ofSeconds(3);
    private final Jdk8 jdk8 = new Jdk8();

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Jdk8 getJdk8() {
        return jdk8;
    }

    public static class Jdk8 {
        private int corePoolSize = 0;
        private int maxPoolSize = 0;
        private Duration keepAlive = Duration.ofSeconds(60);
        private int queueCapacity = 200;
        private String rejectedPolicy = "caller_runs";

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public Duration getKeepAlive() {
            return keepAlive;
        }

        public void setKeepAlive(Duration keepAlive) {
            this.keepAlive = keepAlive;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public String getRejectedPolicy() {
            return rejectedPolicy;
        }

        public void setRejectedPolicy(String rejectedPolicy) {
            this.rejectedPolicy = rejectedPolicy;
        }
    }
}
