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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpMethod.GET;

// can't copy tests from Boot because it uses spring-test, which depends on spring-web (this module)
class MicrometerClientHttpInterceptorTests {

	private final ClientHttpRequestFactory requestFactory = mock(ClientHttpRequestFactory.class);

	private final ClientHttpRequest request = mock(ClientHttpRequest.class);

	private final ClientHttpResponse response = mock(ClientHttpResponse.class);

	private final ResponseErrorHandler errorHandler = mock(ResponseErrorHandler.class);

	@SuppressWarnings("unchecked")
	private final HttpMessageConverter<String> converter = mock(HttpMessageConverter.class);

	private final RestTemplate template = new RestTemplate(List.of(converter));

	private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

	@BeforeEach
	void setup() {
		template.getInterceptors().add(0, new MicrometerClientHttpInterceptor(meterRegistry));
		template.setRequestFactory(requestFactory);
		template.setErrorHandler(errorHandler);
	}

	@Test
	void metricsForTemplatedGet() throws Exception {
		mockSentRequest(GET, "https://example.com/hotels/42/bookings/21");
		mockResponseStatus(HttpStatus.OK);

		template.execute("https://example.com/hotels/{hotel}/bookings/{booking}", GET,
				null, null, "42", "21");

		assertThat(meterRegistry.get("http.client.requests").tags(
				"method", "GET",
				"uri", "UNKNOWN", // TODO capture URI template
				"exception", "None",
				"status", "200",
				"outcome", "SUCCESS"
		).timer().count()).isEqualTo(1);

		verify(response).close();
	}

//	@Test
//	void interceptRestTemplate() {
//		this.mockServer.expect(MockRestRequestMatchers.requestTo("/test/123"))
//				.andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
//				.andRespond(MockRestResponseCreators.withSuccess("OK", MediaType.APPLICATION_JSON));
//		String result = this.restTemplate.getForObject("/test/{id}", String.class, 123);
//		assertThat(this.registry.find("http.client.requests").meters())
//				.anySatisfy((m) -> assertThat(m.getId().getTags().stream().map(Tag::getKey)).doesNotContain("bucket"));
//		assertThat(this.registry.get("http.client.requests").tags("method", "GET", "uri", "/test/{id}", "status", "200")
//				.timer().count()).isEqualTo(1);
//		assertThat(result).isEqualTo("OK");
//		this.mockServer.verify();
//	}
//
//	@Test
//	void normalizeUriToContainLeadingSlash() {
//		this.mockServer.expect(MockRestRequestMatchers.requestTo("/test/123"))
//				.andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
//				.andRespond(MockRestResponseCreators.withSuccess("OK", MediaType.APPLICATION_JSON));
//		String result = this.restTemplate.getForObject("test/{id}", String.class, 123);
//		this.registry.get("http.client.requests").tags("uri", "/test/{id}").timer();
//		assertThat(result).isEqualTo("OK");
//		this.mockServer.verify();
//	}
//
//	@Test
//	void interceptRestTemplateWithUri() throws URISyntaxException {
//		this.mockServer.expect(MockRestRequestMatchers.requestTo("http://localhost/test/123"))
//				.andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
//				.andRespond(MockRestResponseCreators.withSuccess("OK", MediaType.APPLICATION_JSON));
//		String result = this.restTemplate.getForObject(new URI("http://localhost/test/123"), String.class);
//		assertThat(result).isEqualTo("OK");
//		this.registry.get("http.client.requests").tags("uri", "/test/123").timer();
//		this.mockServer.verify();
//	}

//	@Test
//	void interceptNestedRequest() {
//		this.mockServer.expect(MockRestRequestMatchers.requestTo("/test/123"))
//				.andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
//				.andRespond(MockRestResponseCreators.withSuccess("OK", MediaType.APPLICATION_JSON));
//
//		RestTemplate nestedRestTemplate = new RestTemplate();
//		MockRestServiceServer nestedMockServer = MockRestServiceServer.createServer(nestedRestTemplate);
//		nestedMockServer.expect(MockRestRequestMatchers.requestTo("/nestedTest/124"))
//				.andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
//				.andRespond(MockRestResponseCreators.withSuccess("OK", MediaType.APPLICATION_JSON));
//		this.customizer.customize(nestedRestTemplate);
//
//		TestInterceptor testInterceptor = new TestInterceptor(nestedRestTemplate);
//		this.restTemplate.getInterceptors().add(testInterceptor);
//
//		this.restTemplate.getForObject("/test/{id}", String.class, 123);
//		this.registry.get("http.client.requests").tags("uri", "/test/{id}").timer();
//		this.registry.get("http.client.requests").tags("uri", "/nestedTest/{nestedId}").timer();
//
//		this.mockServer.verify();
//		nestedMockServer.verify();
//	}


	private static final class TestInterceptor implements ClientHttpRequestInterceptor {

		private final RestTemplate restTemplate;

		private TestInterceptor(RestTemplate restTemplate) {
			this.restTemplate = restTemplate;
		}

		@Override
		public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
				throws IOException {
			this.restTemplate.getForObject("/nestedTest/{nestedId}", String.class, 124);
			return execution.execute(request, body);
		}

	}

	private void mockSentRequest(HttpMethod method, String uri) throws Exception {
		mockSentRequest(method, uri, new HttpHeaders());
	}

	private void mockSentRequest(HttpMethod method, String uri, HttpHeaders requestHeaders) throws Exception {
		given(requestFactory.createRequest(new URI(uri), method)).willReturn(request);
		given(request.getHeaders()).willReturn(requestHeaders);
	}

	private void mockResponseStatus(HttpStatus responseStatus) throws Exception {
		given(request.execute()).willReturn(response);
		given(errorHandler.hasError(response)).willReturn(responseStatus.isError());
		given(response.getStatusCode()).willReturn(responseStatus);
		given(response.getRawStatusCode()).willReturn(responseStatus.value());
		given(response.getStatusText()).willReturn(responseStatus.getReasonPhrase());
	}

	private void mockTextPlainHttpMessageConverter() {
		mockHttpMessageConverter(MediaType.TEXT_PLAIN, String.class);
	}

	private void mockHttpMessageConverter(MediaType mediaType, Class<?> type) {
		given(converter.canRead(type, null)).willReturn(true);
		given(converter.canRead(type, mediaType)).willReturn(true);
		given(converter.getSupportedMediaTypes(type)).willReturn(Collections.singletonList(mediaType));
		given(converter.canRead(type, mediaType)).willReturn(true);
		given(converter.canWrite(type, null)).willReturn(true);
		given(converter.canWrite(type, mediaType)).willReturn(true);
	}

	private void mockTextResponseBody(String expectedBody) throws Exception {
		mockResponseBody(expectedBody, MediaType.TEXT_PLAIN);
	}

	private void mockResponseBody(String expectedBody, MediaType mediaType) throws Exception {
		HttpHeaders responseHeaders = new HttpHeaders();
		responseHeaders.setContentType(mediaType);
		responseHeaders.setContentLength(expectedBody.length());
		given(response.getHeaders()).willReturn(responseHeaders);
		given(response.getBody()).willReturn(new ByteArrayInputStream(expectedBody.getBytes()));
		given(converter.read(eq(String.class), any(HttpInputMessage.class))).willReturn(expectedBody);
	}

}
