package mygrant.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import mygrant.common.ApiError;

/**
 * Documents the API and lets Swagger UI send the login JWT as a bearer token. Operations need
 * the token unless they are marked public with an empty @SecurityRequirements. The 401 and 403
 * that SecurityConfig and JwtAuthenticationFilter return are added here, so controllers only
 * declare their own errors, and every error response is documented with the ApiError body.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";
    private static final Pattern QUOTED_ROLE = Pattern.compile("'([A-Z_]+)'");

    /** Swagger UI shows the sections in this order. */
    private static final List<Tag> TAGS = List.of(
            tag("Authentication", "Register, sign in, refresh the access token and sign out. Register, "
                    + "login and refresh are public; paste the `token` they return into **Authorize**."),
            tag("Profile", "The signed-in user's profile and immigration documents. The profile and "
                    + "current-user endpoints also work before onboarding is finished."),
            tag("Account", "Deleting the signed-in user's account, confirmed with a one-time challenge."),
            tag("Policies", "Stored policy records."),
            tag("Policy questions", "Questions answered from indexed policy evidence (RAG), with citations."),
            tag("Notifications", "The signed-in user's policy alerts, including a live event stream."),
            tag("Email alerts", "How alerts are emailed to the signed-in user, and an admin trigger for "
                    + "the daily digest."),
            tag("Support chat", "Messages between applicants and the support team. The staff inbox needs "
                    + "the ADMIN or MANAGER role."),
            tag("Ingestion", "Admin-only policy imports, and the health of every source connector."),
            tag("Ingestion: Visa Bulletin", "Admin-only import and inspection of Visa Bulletin cutoff charts."),
            tag("Ingestion: USCIS processing times", "Admin-only import of saved USCIS processing-time results."),
            tag("Ingestion: News RSS", "Admin-only import of the secondary immigration news feed."),
            tag("Admin", "Admin-only user management."),
            tag("Test clock", "TEMPORARY testing aid that moves the app's date to watch alerts arrive. "
                    + "Present only while `mygrant.test-clock.enabled` is true."),
            tag("Webhooks", "Called by WorkOS, not by API clients. Requests are checked against their "
                    + "`WorkOS-Signature` header instead of a JWT."));

    @Bean
    public OpenAPI myGrantOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("myGRANT API")
                        .description("""
                                Profile-aware immigration policy impact and RAG service.

                                Sign in with `POST /api/v1/auth/login`, then choose **Authorize** and \
                                paste the returned `token`. Every error has the `ApiError` body. List \
                                endpoints return a page: pass `page`, `size` and `sort`, and read the \
                                items from `content`.""")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH))
                .tags(new ArrayList<>(TAGS));
    }

    /** Adds the security errors, and the role a caller needs, to every operation that takes a JWT. */
    @Bean
    public OperationCustomizer securityResponses() {
        return (operation, handlerMethod) -> {
            boolean isPublic = operation.getSecurity() != null && operation.getSecurity().isEmpty();
            if (isPublic) {
                return operation;
            }
            ApiResponses responses = operation.getResponses();
            responses.putIfAbsent("401", new ApiResponse()
                    .description("Not signed in, or the access token is invalid or expired"));
            String roles = requiredRoles(handlerMethod);
            if (roles != null) {
                operation.setDescription(operation.getDescription() == null
                        ? "Requires the " + roles + " role."
                        : operation.getDescription() + "\n\nRequires the " + roles + " role.");
                responses.putIfAbsent("403", new ApiResponse()
                        .description("Signed in without the " + roles + " role, or onboarding is unfinished"));
            } else if (!JwtAuthenticationFilter.ONBOARDING_PATHS.contains(path(handlerMethod))) {
                responses.putIfAbsent("403", new ApiResponse()
                        .description("Onboarding is unfinished: the profile must be completed first"));
            }
            return operation;
        };
    }

    /** Every 4xx and 5xx response, declared or added above, has the ApiError body. */
    @Bean
    public OpenApiCustomizer apiErrorBodies() {
        return openApi -> {
            if (openApi.getComponents().getSchemas() == null
                    || !openApi.getComponents().getSchemas().containsKey("ApiError")) {
                ModelConverters.getInstance().read(ApiError.class).forEach(openApi.getComponents()::addSchemas);
            }
            Content apiError = new Content().addMediaType("application/json",
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError")));
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation ->
                    operation.getResponses().forEach((code, response) -> {
                        if (code.startsWith("4") || code.startsWith("5")) {
                            response.setContent(apiError);
                        }
                    })));
        };
    }

    /** "ADMIN" or "ADMIN or MANAGER" from a method's or class's @PreAuthorize; null when there is none. */
    private static String requiredRoles(HandlerMethod handlerMethod) {
        PreAuthorize rule = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class);
        if (rule == null) {
            rule = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class);
        }
        if (rule == null) {
            return null;
        }
        List<String> roles = new ArrayList<>();
        Matcher matcher = QUOTED_ROLE.matcher(rule.value());
        while (matcher.find()) {
            roles.add(matcher.group(1));
        }
        return String.join(" or ", roles);
    }

    private static String path(HandlerMethod handlerMethod) {
        return firstPath(AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequestMapping.class))
                + firstPath(AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequestMapping.class));
    }

    private static String firstPath(RequestMapping mapping) {
        return mapping == null || mapping.path().length == 0 ? "" : mapping.path()[0];
    }

    private static Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }
}
