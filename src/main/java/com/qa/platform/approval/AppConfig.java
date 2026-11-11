package com.qa.platform.approval;

import com.qa.platform.common.Workspace;
import com.qa.platform.generate.CaseStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(PlatformProperties.class)
public class AppConfig {

  @Bean
  CaseStore caseStore() { return new CaseStore(Workspace.TESTCASES); }

  /**
   * Users come from platform.users ("name:password:ROLE", ROLE = REVIEWER or VIEWER), normally via env
   * PLATFORM_USERS. Replace with your SSO (spring-boot-starter-oauth2-client) for production.
   */
  @Bean
  InMemoryUserDetailsManager users(PlatformProperties props,
                                   @org.springframework.beans.factory.annotation.Value("${server.address:127.0.0.1}") String address) {
    boolean localOnly = address.equals("127.0.0.1") || address.equals("localhost") || address.equals("::1");
    boolean defaultUser = props.users() != null && props.users().stream().anyMatch(u -> u.trim().equals("admin:admin:REVIEWER"));
    if (!localOnly && defaultUser) {
      // the UI can start test runs: on a shared address it must not keep the built-in local password
      throw new IllegalStateException("BIND_ADDRESS=" + address + " opens the UI to the network: set PLATFORM_USERS "
          + "with real passwords (the default admin:admin is only allowed on 127.0.0.1)");
    }
    PasswordEncoder enc = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    List<UserDetails> list = new ArrayList<>();
    for (String u : props.users() == null ? List.<String>of() : props.users()) {
      String[] p = u.split(":");
      if (p.length < 3) continue;
      list.add(User.withUsername(p[0]).password(enc.encode(p[1])).roles(p[2].trim()).build());
    }
    if (list.isEmpty()) throw new IllegalStateException("No users configured: set PLATFORM_USERS=name:password:REVIEWER");
    return new InMemoryUserDetailsManager(list);
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(a -> a
            .requestMatchers("/actuator/health").permitAll()
            .requestMatchers(org.springframework.http.HttpMethod.GET, "/**").hasAnyRole("REVIEWER", "VIEWER")
            .anyRequest().hasRole("REVIEWER"))
        .httpBasic(Customizer.withDefaults())
        .formLogin(Customizer.withDefaults())
        // the UI is same-origin and authenticated; JSON API calls send the X-Requested-With header
        .csrf(c -> c.ignoringRequestMatchers("/api/**"));
    return http.build();
  }
}
