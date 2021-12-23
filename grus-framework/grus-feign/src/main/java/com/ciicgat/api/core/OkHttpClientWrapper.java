/*
 * Copyright 2007-2021, CIIC Guanaitong, Co., Ltd.
 * All rights reserved.
 */

package com.ciicgat.api.core;

import com.ciicgat.api.core.kubernetes.KubernetesClientConfig;
import com.ciicgat.sdk.util.system.WorkRegion;
import feign.Client;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.ciicgat.api.core.FeignHttpClient.CONNECT_TIMEOUT_TAG;
import static com.ciicgat.api.core.FeignHttpClient.K8S_TARGET_TAG;
import static com.ciicgat.api.core.FeignHttpClient.READ_TIMEOUT_TAG;

/**
 * This performance directs Feign's http requests to
 * <a href="http://square.github.io/okhttp/">OkHttp</a>, which enables SPDY and better network
 * control. Ex.
 * <p>
 * GitHub github = Feign.builder().client(new OkHttpClient()).target(GitHub.class,
 * "https://api.github.com");
 */
public final class OkHttpClientWrapper implements Client {
    private static final Logger LOGGER = LoggerFactory.getLogger(OkHttpClientWrapper.class);

    private final okhttp3.OkHttpClient delegate;

    private static final boolean isNotPrepare = !WorkRegion.getCurrentWorkRegion().isPrepare();

    OkHttpClientWrapper(okhttp3.OkHttpClient delegate) {
        this.delegate = delegate;
    }


    static Request toOkHttpRequest(feign.Request input, boolean useClientLb, HttpUrl clientLbTargetUrl) {
        Request.Builder requestBuilder = new Request.Builder();
        if (useClientLb) {
            requestBuilder.url(clientLbTargetUrl);
        } else {
            requestBuilder.url(input.url());
        }
        MediaType mediaType = null;
        boolean hasAcceptHeader = false;
        for (String field : input.headers().keySet()) {
            if (CONNECT_TIMEOUT_TAG.equals(field)
                    || READ_TIMEOUT_TAG.equals(field)
                    || K8S_TARGET_TAG.equals(field)) {
                continue;
            }
            if (field.equalsIgnoreCase("Accept")) {
                hasAcceptHeader = true;
            }

            for (String value : input.headers().get(field)) {
                requestBuilder.addHeader(field, value);
                if (field.equalsIgnoreCase("Content-Type")) {
                    mediaType = MediaType.parse(value);
                    if (input.charset() != null) {
                        mediaType.charset(input.charset());
                    }
                }
            }
        }

        // Some servers choke on the default accept string.
        if (!hasAcceptHeader) {
            requestBuilder.addHeader("Accept", "*/*");
        }
        byte[] inputBody = input.body();
        boolean isMethodWithBody = feign.Request.HttpMethod.POST == input.httpMethod() || feign.Request.HttpMethod.PUT == input.httpMethod();
        if (isMethodWithBody) {
            requestBuilder.removeHeader("Content-Type");
            if (inputBody == null) {
                // write an empty BODY to conform with trace 2.4.0+
                // http://johnfeng.github.io/blog/2015/06/30/okhttp-updates-post-wouldnt-be-allowed-to-have-null-body/
                inputBody = new byte[0];
            }
        }

        RequestBody body = inputBody != null ? RequestBody.create(mediaType, inputBody) : null;
        requestBuilder.method(input.httpMethod().name(), body);
        return requestBuilder.build();
    }

    private static feign.Response toFeignResponse(Response response, feign.Request request)
            throws IOException {
        return feign.Response.builder()
                .status(response.code())
                .reason(response.message())
                .request(request)
                .headers(toMap(response.headers()))
                .body(toBody(response.body()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Collection<String>> toMap(Headers headers) {
        return (Map) headers.toMultimap();
    }

    private static feign.Response.Body toBody(final ResponseBody input) throws IOException {
        if (input == null || input.contentLength() == 0) {
            if (input != null) {
                input.close();
            }
            return null;
        }
        final Integer length = input.contentLength() >= 0 && input.contentLength() <= Integer.MAX_VALUE
                ? (int) input.contentLength()
                : null;

        return new feign.Response.Body() {

            @Override
            public void close() {
                input.close();
            }

            @Override
            public Integer length() {
                return length;
            }

            @Override
            public boolean isRepeatable() {
                return false;
            }

            @Override
            public InputStream asInputStream() {
                return input.byteStream();
            }

            @Override
            public Reader asReader() {
                return input.charStream();
            }

            @Override
            public Reader asReader(Charset charset) {
                return asReader();
            }
        };
    }

    @Override
    public feign.Response execute(feign.Request input, feign.Request.Options options)
            throws IOException {
        Map<String, Collection<String>> headers = input.headers();
        long connectTimeoutMillis = getValueFromHeader(headers, CONNECT_TIMEOUT_TAG, options.connectTimeoutMillis());
        long readTimeoutMillis = getValueFromHeader(headers, READ_TIMEOUT_TAG, options.readTimeoutMillis());
        Collection<String> values = headers.get(K8S_TARGET_TAG);
        boolean isK8sService = !CollectionUtils.isEmpty(values);
        if (isK8sService) {
            long globalMaxConnectTimeout = KubernetesClientConfig.getConfig().getConnectTimeout();
            if (connectTimeoutMillis > globalMaxConnectTimeout) {
                connectTimeoutMillis = globalMaxConnectTimeout;
            }
        }
        // fall back to k8s clusterIp service
        // or app not enable client lb config
        okhttp3.OkHttpClient realClient = delegate.newBuilder()
                .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
                .followRedirects(options.isFollowRedirects())
                .build();
        Request okHttpRequest = toOkHttpRequest(input, false, null);
        return execute0(input, realClient, okHttpRequest);
    }


    private feign.Response execute0(feign.Request input, okhttp3.OkHttpClient realClient, Request okHttpRequest) throws IOException {
        Response response = realClient.newCall(okHttpRequest).execute();
        return toFeignResponse(response, input).toBuilder().request(input).build();
    }

    private int getValueFromHeader(Map<String, Collection<String>> headers, String key, int defaultValue) {
        Collection<String> collection = headers.get(key);
        if (collection == null || collection.size() == 0) {
            return defaultValue;
        }
        try {
            String v = collection.stream().findAny().orElseThrow();
            return Integer.parseInt(v);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
