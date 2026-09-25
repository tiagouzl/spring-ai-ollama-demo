---
type: lesson
title: security-starter-form-login-chain-breaks-interceptor-auth
summary: Adding a Spring Security starter silently installs a form-login chain that hijacks every endpoint — exclude the security auto-configs or explicit chains must exist before the dependency lands.
tags:
  - auth
  - build
last_verified_commit: 4abb6142c1ac8cf5f288579a82972f5456a7f6b7
status: active
---

## Situation

Adding `spring-boot-starter-oauth2-resource-server` for the OIDC mode (Task 1, commit `d2289cd`) pulled in `spring-boot-starter-security`, and Spring Security auto-configuration installed a default form-login `SecurityFilterChain` on app startup: 46 of 94 tests broke with login-page responses instead of the interceptor-based 401s this app relies on. The app authenticates via `ApiKeyAuthInterceptor` + `ActuatorApiKeyFilter` (Servlet filters), not Spring Security chains — so ANY Security starter activates machinery the app never asked for. Fix in two stages: Task 1 excluded five auto-configurations in `application.yml` (`SecurityAutoConfiguration`, `SecurityFilterAutoConfiguration`, `UserDetailsServiceAutoConfiguration`, `OAuth2ResourceServerAutoConfiguration`, `ManagementWebSecurityAutoConfiguration`); Task 3 (commit `3b0bd5e`) then had to add `@EnableWebSecurity` to `OidcSecurityConfig`, because with `SecurityAutoConfiguration` excluded nothing wires explicit `SecurityFilterChain` beans into a running `FilterChainProxy`. The coupling is now documented as a comment on the class (commit `4abb614`).

## Why It Mattered

The failure mode is silent at compile time and loud at test time (dozens of red tests with confusing login-redirect bodies), and the two halves of the fix are separated by two tasks — an agent doing only the dependency step would ship a broken app, and an agent doing only the chains step would find its chains inert. This repo already has the sibling lesson `new-dashscope-autoconfig-classes-need-exclude-list-update`: every new starter re-opens the exclude list.

## Rule

When adding any Spring Security starter to this app, land both halves together: exclude the five security auto-configurations in `application.yml` AND ensure exactly one `@EnableWebSecurity` class holds all `SecurityFilterChain` beans — never add a second `@EnableWebSecurity` anywhere.

## When to Apply

Any change touching `pom.xml` security dependencies, `application.yml` `spring.autoconfigure.exclude`, `OidcSecurityConfig`, or any new `@Configuration` that might carry web-security annotations. Also when a test suite suddenly returns login pages/302 redirects.

## When NOT to Apply

Do not touch the exclude list when adding NON-security starters (normal Boot auto-config is wanted), and do not add exclusions for classes Spring Security does not ship — verify the exclusion still matches the actual auto-configuration class names for the Boot version in use (renamed classes fail silently as unused exclusions on some Boot versions).
