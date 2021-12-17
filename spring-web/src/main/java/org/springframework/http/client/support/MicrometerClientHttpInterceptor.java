/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.http.client.support;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.http.HttpTagsProvider;
import io.micrometer.core.instrument.tracing.context.HttpClientHandlerContext;
import io.micrometer.core.instrument.transport.http.HttpClientRequest;
import io.micrometer.core.instrument.transport.http.HttpClientResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.NamedThreadLocal;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.util.UriTemplateHandler;

/**
 * Instruments {@link org.springframework.web.client.RestTemplate} calls using the Micrometer API.
 * Records metrics for each exchange intercepted.
 *
 * @author Jon Schneider
 * @author Phillip Webb
 * @author Tommy Ludwig
 * @since 6.0
 */
public class MicrometerClientHttpInterceptor implements ClientHttpRequestInterceptor {

	private static final Log logger = LogFactory.getLog(MicrometerClientHttpInterceptor.class);

	private static final ThreadLocal<Deque<String>> urlTemplate = new UrlTemplateThreadLocal();

	private final MeterRegistry meterRegistry;
	private final HttpTagsProvider tagsProvider;

	public MicrometerClientHttpInterceptor(MeterRegistry meterRegistry) {
		this(meterRegistry, HttpTagsProvider.DEFAULT);
	}

	public MicrometerClientHttpInterceptor(MeterRegistry meterRegistry, HttpTagsProvider tagsProvider) {
		this.meterRegistry = meterRegistry;
		this.tagsProvider = tagsProvider;
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		HttpClientRequest wrappedRequest = new SpringHttpClientRequest(request);
		HttpClientHandlerContext handlerContext = new HttpClientHandlerContext(wrappedRequest, this.tagsProvider);
		Timer.Sample sample = Timer.start(this.meterRegistry, handlerContext);
		ClientHttpResponse response = null;
		try {
			response = execution.execute(request, body);
			return response;
		}
		finally {
			try {
				handlerContext.setResponse(new SpringHttpClientResponse(response, wrappedRequest));
				sample.stop(getTimeBuilder());
			}
			catch (Exception ex) {
				logger.info("Failed to record metrics.", ex);
			}
			if (urlTemplate.get().isEmpty()) {
				urlTemplate.remove();
			}
		}
	}

	private Timer.Builder getTimeBuilder() {
		return Timer.builder("http.client.requests")
				.description("Timer of RestTemplate operation");
	}

	// Copied from Spring Boot
	// TODO: How should we get the URI template?
	// Boot uses a RestTemplateCustomizer to configure this as the RestTemplate's UriTemplateHandler
	private final class CapturingUriTemplateHandler implements UriTemplateHandler {

		private final UriTemplateHandler delegate;

		private CapturingUriTemplateHandler(UriTemplateHandler delegate) {
			this.delegate = delegate;
		}

		@Override
		public URI expand(String url, Map<String, ?> arguments) {
			urlTemplate.get().push(url);
			return this.delegate.expand(url, arguments);
		}

		@Override
		public URI expand(String url, Object... arguments) {
			urlTemplate.get().push(url);
			return this.delegate.expand(url, arguments);
		}

	}

	private static final class UrlTemplateThreadLocal extends NamedThreadLocal<Deque<String>> {

		private UrlTemplateThreadLocal() {
			super("Rest Template URL Template");
		}

		@Override
		protected Deque<String> initialValue() {
			return new LinkedList<>();
		}

	}

	private static final class SpringHttpClientRequest implements HttpClientRequest {

		private final HttpRequest request;

		private SpringHttpClientRequest(HttpRequest request) {
			this.request = request;
		}

		@Override
		public void header(String name, String value) {
			this.request.getHeaders().set(name, value);
		}

		@Override
		public String method() {
			return this.request.getMethod().name();
		}

		@Override
		public String path() {
			return this.request.getURI().getPath();
		}

		@Override
		public String route() {
			// TODO use templated route
			return HttpClientRequest.super.route();
		}

		@Override
		public String url() {
			try {
				return this.request.getURI().toURL().toString();
			}
			catch (MalformedURLException ex) {
				return null;
			}
		}

		@Override
		public String header(String name) {
			return this.request.getHeaders().getFirst(name);
		}

		@Override
		public String remoteIp() {
			// TODO
			return HttpClientRequest.super.remoteIp();
		}

		@Override
		public int remotePort() {
			// TODO
			return HttpClientRequest.super.remotePort();
		}

		@Override
		public Collection<String> headerNames() {
			return this.request.getHeaders().keySet();
		}

		@Override
		public Object unwrap() {
			return this.request;
		}
	}

	private static class SpringHttpClientResponse implements HttpClientResponse {

		@Nullable
		private final ClientHttpResponse response;
		private final HttpClientRequest request;

		public SpringHttpClientResponse(@Nullable ClientHttpResponse response, HttpClientRequest request) {
			this.response = response;
			this.request = request;
		}

		@Override
		public int statusCode() {
			if (this.response == null) {
				return 0;
			}
			try {
				return this.response.getRawStatusCode();
			}
			catch (IOException ex) {
				return 0;
			}
		}

		@Override
		@Nullable
		public String header(String header) {
			if (this.response == null) {
				return null;
			}
			return this.response.getHeaders().getFirst(header);
		}

		@Override
		public Collection<String> headerNames() {
			if (this.response == null) {
				return Collections.emptySet();
			}
			return this.response.getHeaders().keySet();
		}

		@Override
		@Nullable
		public Object unwrap() {
			return this.response;
		}

		@Override
		public HttpClientRequest request() {
			return this.request;
		}

	}
}
