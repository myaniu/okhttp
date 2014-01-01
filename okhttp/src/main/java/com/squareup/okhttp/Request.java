/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp;

import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.HeaderParser;
import com.squareup.okhttp.internal.http.Headers;
import com.squareup.okhttp.internal.http.HttpDate;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An HTTP request. Instances of this class are immutable if their {@link #body}
 * is null or itself immutable.
 *
 * <h3>Warning: Experimental OkHttp 2.0 API</h3>
 * This class is in beta. APIs are subject to change!
 */
public final class Request {
  private final URL url;
  private final String method;
  private final Headers headers;
  private final Body body;
  private final Object tag;

  private volatile ParsedHeaders parsedHeaders; // Lazily initialized.
  private volatile URI uri; // Lazily initialized.

  private Request(Builder builder) {
    this.url = builder.url;
    this.method = builder.method;
    this.headers = builder.headers.build();
    this.body = builder.body;
    this.tag = builder.tag != null ? builder.tag : this;
  }

  public URL url() {
    return url;
  }

  public URI uri() throws IOException {
    try {
      URI result = uri;
      return result != null ? result : (uri = Platform.get().toUriLenient(url));
    } catch (URISyntaxException e) {
      throw new IOException(e.getMessage());
    }
  }

  public String urlString() {
    return url.toString();
  }

  public String method() {
    return method;
  }

  public String header(String name) {
    return headers.get(name);
  }

  public List<String> headers(String name) {
    return headers.values(name);
  }

  public Set<String> headerNames() {
    return headers.names();
  }

  Headers headers() {
    return headers;
  }

  public int headerCount() {
    return headers.length();
  }

  public String headerName(int index) {
    return headers.getFieldName(index);
  }

  public String headerValue(int index) {
    return headers.getValue(index);
  }

  public Body body() {
    return body;
  }

  public Object tag() {
    return tag;
  }

  public Builder newBuilder() {
    return new Builder(this);
  }

  public boolean isChunked() {
    return "chunked".equalsIgnoreCase(parsedHeaders().transferEncoding);
  }

  public boolean hasConnectionClose() {
    return "close".equalsIgnoreCase(parsedHeaders().connection);
  }

  public Headers getHeaders() {
    return headers;
  }

  public boolean isNoCache() {
    return parsedHeaders().noCache;
  }

  public int getMaxAgeSeconds() {
    return parsedHeaders().maxAgeSeconds;
  }

  public int getMaxStaleSeconds() {
    return parsedHeaders().maxStaleSeconds;
  }

  public int getMinFreshSeconds() {
    return parsedHeaders().minFreshSeconds;
  }

  public boolean isOnlyIfCached() {
    return parsedHeaders().onlyIfCached;
  }

  public boolean hasAuthorization() {
    return parsedHeaders().hasAuthorization;
  }

  // TODO: Make non-public. This conflicts with the Body's content length!
  public long getContentLength() {
    return parsedHeaders().contentLength;
  }

  public String getTransferEncoding() {
    return parsedHeaders().transferEncoding;
  }

  public String getUserAgent() {
    return parsedHeaders().userAgent;
  }

  public String getHost() {
    return parsedHeaders().host;
  }

  public String getConnection() {
    return parsedHeaders().connection;
  }

  public String getAcceptEncoding() {
    return parsedHeaders().acceptEncoding;
  }

  // TODO: Make non-public. This conflicts with the Body's content type!
  public String getContentType() {
    return parsedHeaders().contentType;
  }

  public String getIfModifiedSince() {
    return parsedHeaders().ifModifiedSince;
  }

  public String getIfNoneMatch() {
    return parsedHeaders().ifNoneMatch;
  }

  public String getProxyAuthorization() {
    return parsedHeaders().proxyAuthorization;
  }

  /**
   * Returns true if the request contains conditions that save the server from
   * sending a response that the client has locally. When a request is enqueued
   * with conditions, built-in response caches won't be used for that request.
   */
  public boolean hasConditions() {
    return parsedHeaders().ifModifiedSince != null || parsedHeaders().ifNoneMatch != null;
  }

  private ParsedHeaders parsedHeaders() {
    ParsedHeaders result = parsedHeaders;
    return result != null ? result : (parsedHeaders = new ParsedHeaders(headers));
  }

  public boolean isHttps() {
    return url().getProtocol().equals("https");
  }

  /** Parsed request headers, computed on-demand and cached. */
  private static class ParsedHeaders {
    /** Don't use a cache to satisfy this request. */
    private boolean noCache;
    private int maxAgeSeconds = -1;
    private int maxStaleSeconds = -1;
    private int minFreshSeconds = -1;

    /**
     * This field's name "only-if-cached" is misleading. It actually means "do
     * not use the network". It is set by a client who only wants to make a
     * request if it can be fully satisfied by the cache. Cached responses that
     * would require validation (ie. conditional gets) are not permitted if this
     * header is set.
     */
    private boolean onlyIfCached;

    /**
     * True if the request contains an authorization field. Although this isn't
     * necessarily a shared cache, it follows the spec's strict requirements for
     * shared caches.
     */
    private boolean hasAuthorization;

    private long contentLength = -1;
    private String transferEncoding;
    private String userAgent;
    private String host;
    private String connection;
    private String acceptEncoding;
    private String contentType;
    private String ifModifiedSince;
    private String ifNoneMatch;
    private String proxyAuthorization;

    public ParsedHeaders(Headers headers) {
      HeaderParser.CacheControlHandler handler = new HeaderParser.CacheControlHandler() {
        @Override public void handle(String directive, String parameter) {
          if ("no-cache".equalsIgnoreCase(directive)) {
            noCache = true;
          } else if ("max-age".equalsIgnoreCase(directive)) {
            maxAgeSeconds = HeaderParser.parseSeconds(parameter);
          } else if ("max-stale".equalsIgnoreCase(directive)) {
            maxStaleSeconds = HeaderParser.parseSeconds(parameter);
          } else if ("min-fresh".equalsIgnoreCase(directive)) {
            minFreshSeconds = HeaderParser.parseSeconds(parameter);
          } else if ("only-if-cached".equalsIgnoreCase(directive)) {
            onlyIfCached = true;
          }
        }
      };

      for (int i = 0; i < headers.length(); i++) {
        String fieldName = headers.getFieldName(i);
        String value = headers.getValue(i);
        if ("Cache-Control".equalsIgnoreCase(fieldName)) {
          HeaderParser.parseCacheControl(value, handler);
        } else if ("Pragma".equalsIgnoreCase(fieldName)) {
          if ("no-cache".equalsIgnoreCase(value)) {
            noCache = true;
          }
        } else if ("If-None-Match".equalsIgnoreCase(fieldName)) {
          ifNoneMatch = value;
        } else if ("If-Modified-Since".equalsIgnoreCase(fieldName)) {
          ifModifiedSince = value;
        } else if ("Authorization".equalsIgnoreCase(fieldName)) {
          hasAuthorization = true;
        } else if ("Content-Length".equalsIgnoreCase(fieldName)) {
          try {
            contentLength = Long.parseLong(value);
          } catch (NumberFormatException ignored) {
          }
        } else if ("Transfer-Encoding".equalsIgnoreCase(fieldName)) {
          transferEncoding = value;
        } else if ("User-Agent".equalsIgnoreCase(fieldName)) {
          userAgent = value;
        } else if ("Host".equalsIgnoreCase(fieldName)) {
          host = value;
        } else if ("Connection".equalsIgnoreCase(fieldName)) {
          connection = value;
        } else if ("Accept-Encoding".equalsIgnoreCase(fieldName)) {
          acceptEncoding = value;
        } else if ("Content-Type".equalsIgnoreCase(fieldName)) {
          contentType = value;
        } else if ("Proxy-Authorization".equalsIgnoreCase(fieldName)) {
          proxyAuthorization = value;
        }
      }
    }
  }

  public abstract static class Body {
    /** Returns the Content-Type header for this body. */
    public abstract MediaType contentType();

    /**
     * Returns the number of bytes that will be written to {@code out} in a call
     * to {@link #writeTo}, or -1 if that count is unknown.
     */
    public long contentLength() {
      return -1;
    }

    /** Writes the content of this request to {@code out}. */
    public abstract void writeTo(OutputStream out) throws IOException;

    /**
     * Returns a new request body that transmits {@code content}. If {@code
     * contentType} lacks a charset, this will use UTF-8.
     */
    public static Body create(MediaType contentType, String content) {
      contentType = contentType.charset() != null
          ? contentType
          : MediaType.parse(contentType + "; charset=utf-8");
      try {
        byte[] bytes = content.getBytes(contentType.charset().name());
        return create(contentType, bytes);
      } catch (UnsupportedEncodingException e) {
        throw new AssertionError();
      }
    }

    /** Returns a new request body that transmits {@code content}. */
    public static Body create(final MediaType contentType, final byte[] content) {
      if (contentType == null) throw new NullPointerException("contentType == null");
      if (content == null) throw new NullPointerException("content == null");

      return new Body() {
        @Override public MediaType contentType() {
          return contentType;
        }

        @Override public long contentLength() {
          return content.length;
        }

        @Override public void writeTo(OutputStream out) throws IOException {
          out.write(content);
        }
      };
    }

    /** Returns a new request body that transmits the content of {@code file}. */
    public static Body create(final MediaType contentType, final File file) {
      if (contentType == null) throw new NullPointerException("contentType == null");
      if (file == null) throw new NullPointerException("content == null");

      return new Body() {
        @Override public MediaType contentType() {
          return contentType;
        }

        @Override public long contentLength() {
          return file.length();
        }

        @Override public void writeTo(OutputStream out) throws IOException {
          long length = contentLength();
          if (length == 0) return;

          InputStream in = null;
          try {
            in = new FileInputStream(file);
            byte[] buffer = new byte[(int) Math.min(8192, length)];
            for (int c; (c = in.read(buffer)) != -1; ) {
              out.write(buffer, 0, c);
            }
          } finally {
            Util.closeQuietly(in);
          }
        }
      };
    }
  }

  public static class Builder {
    private URL url;
    private String method;
    private final Headers.Builder headers;
    private Body body;
    private Object tag;

    public Builder() {
      this.method = "GET";
      this.headers = new Headers.Builder();
    }

    private Builder(Request request) {
      this.url = request.url;
      this.method = request.method;
      this.body = request.body;
      this.tag = request.tag;
      this.headers = request.headers.newBuilder();
    }

    public Builder url(String url) {
      try {
        return url(new URL(url));
      } catch (MalformedURLException e) {
        throw new IllegalArgumentException("Malformed URL: " + url);
      }
    }

    public Builder url(URL url) {
      if (url == null) throw new IllegalArgumentException("url == null");
      this.url = url;
      return this;
    }

    /**
     * Sets the header named {@code name} to {@code value}. If this request
     * already has any headers with that name, they are all replaced.
     */
    public Builder header(String name, String value) {
      headers.set(name, value);
      return this;
    }

    /**
     * Adds a header with {@code name} and {@code value}. Prefer this method for
     * multiply-valued headers like "Cookie".
     */
    public Builder addHeader(String name, String value) {
      headers.add(name, value);
      return this;
    }

    public void removeHeader(String name) {
      headers.removeAll(name);
    }

    public Builder setChunked() {
      headers.set("Transfer-Encoding", "chunked");
      return this;
    }

    // TODO: conflict's with the body's content type.
    public Builder setContentLength(long contentLength) {
      headers.set("Content-Length", Long.toString(contentLength));
      return this;
    }

    public void setUserAgent(String userAgent) {
      headers.set("User-Agent", userAgent);
    }

    // TODO: this shouldn't be public.
    public void setHost(String host) {
      headers.set("Host", host);
    }

    public void setConnection(String connection) {
      headers.set("Connection", connection);
    }

    public void setAcceptEncoding(String acceptEncoding) {
      headers.set("Accept-Encoding", acceptEncoding);
    }

    // TODO: conflict's with the body's content type.
    public void setContentType(String contentType) {
      headers.set("Content-Type", contentType);
    }

    public void setIfModifiedSince(Date date) {
      headers.set("If-Modified-Since", HttpDate.format(date));
    }

    public void setIfNoneMatch(String ifNoneMatch) {
      headers.set("If-None-Match", ifNoneMatch);
    }

    public void addCookies(Map<String, List<String>> cookieHeaders) {
      for (Map.Entry<String, List<String>> entry : cookieHeaders.entrySet()) {
        String key = entry.getKey();
        if (("Cookie".equalsIgnoreCase(key) || "Cookie2".equalsIgnoreCase(key))
            && !entry.getValue().isEmpty()) {
          headers.add(key, buildCookieHeader(entry.getValue()));
        }
      }
    }

    /**
     * Send all cookies in one big header, as recommended by
     * <a href="http://tools.ietf.org/html/rfc6265#section-4.2.1">RFC 6265</a>.
     */
    private String buildCookieHeader(List<String> cookies) {
      if (cookies.size() == 1) return cookies.get(0);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < cookies.size(); i++) {
        if (i > 0) sb.append("; ");
        sb.append(cookies.get(i));
      }
      return sb.toString();
    }

    public Builder get() {
      return method("GET", null);
    }

    public Builder head() {
      return method("HEAD", null);
    }

    public Builder post(Body body) {
      return method("POST", body);
    }

    public Builder put(Body body) {
      return method("PUT", body);
    }

    public Builder method(String method, Body body) {
      if (method == null || method.length() == 0) {
        throw new IllegalArgumentException("method == null || method.length() == 0");
      }
      this.method = method;
      this.body = body;
      return this;
    }

    /**
     * Attaches {@code tag} to the request. It can be used later to cancel the
     * request. If the tag is unspecified or null, the request is canceled by
     * using the request itself as the tag.
     */
    public Builder tag(Object tag) {
      this.tag = tag;
      return this;
    }

    public Request build() {
      if (url == null) throw new IllegalStateException("url == null");
      return new Request(this);
    }
  }
}
