package com.sevnis.movieticket.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TransactionIdMdcFilter extends OncePerRequestFilter {

  // ASSUMPTION 1: transactionId also exists in the request header
  public static final String TRANSACTION_ID_HEADER = "X-Transaction-Id";
  public static final String TRANSACTION_ID_MDC_KEY = "transactionId";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain filterChain) throws ServletException, IOException {

    try {
      String transactionId = resolveTransactionId(request);
      MDC.put(TRANSACTION_ID_MDC_KEY, transactionId);
      response.setHeader(TRANSACTION_ID_HEADER, transactionId);
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(TRANSACTION_ID_MDC_KEY);
    }
  }

  private String resolveTransactionId(HttpServletRequest request) {
    String headerValue = request.getHeader(TRANSACTION_ID_HEADER);
    return StringUtils.hasText(headerValue) ? headerValue : UUID.randomUUID().toString();
  }
}