package org.metadatacenter.cedar.resource.resources;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.container.Suspended;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Reflection over the declared JAX-RS surface of the four artifact-type resource classes. Both
 * surface tests drive off this helper: the snapshot test renders the canonical description, and
 * the routes test enumerates the endpoints to probe against the booted application.
 *
 * The description is deliberately canonical and deterministic: one line per endpoint-fact,
 * sorted lexicographically, so any change to the surface produces a minimal, readable diff.
 */
final class ArtifactResourceSurface {

  static final List<Class<?>> RESOURCE_CLASSES = List.of(
      TemplatesResource.class,
      TemplateElementsResource.class,
      TemplateFieldsResource.class,
      TemplateInstancesResource.class);

  private ArtifactResourceSurface() {
  }

  /** One endpoint: an HTTP-verb-annotated method on one of the resource classes. */
  static final class Endpoint {
    final String declaringClass;
    final String methodName;
    final String verb;
    final String fullPath;
    final List<String> produces;
    final List<String> consumes;
    final List<String> parameterFacts;
    final String apiOperationValue;
    final boolean acceptsBody;

    private Endpoint(String declaringClass, String methodName, String verb, String fullPath,
                     List<String> produces, List<String> consumes, List<String> parameterFacts,
                     String apiOperationValue, boolean acceptsBody) {
      this.declaringClass = declaringClass;
      this.methodName = methodName;
      this.verb = verb;
      this.fullPath = fullPath;
      this.produces = produces;
      this.consumes = consumes;
      this.parameterFacts = parameterFacts;
      this.apiOperationValue = apiOperationValue;
      this.acceptsBody = acceptsBody;
    }

    String key() {
      return verb + " " + fullPath;
    }
  }

  /** All endpoints of the four classes, sorted by verb + path (deterministic). */
  static List<Endpoint> endpoints() {
    List<Endpoint> endpoints = new ArrayList<>();
    for (Class<?> resourceClass : RESOURCE_CLASSES) {
      String classPath = resourceClass.getAnnotation(Path.class).value();
      String[] classProduces = valueOf(resourceClass.getAnnotation(Produces.class));
      String[] classConsumes = valueOf(resourceClass.getAnnotation(Consumes.class));
      // getMethods includes inherited public methods, so endpoint-declaring methods carrying
      // JAX-RS annotations on an ancestor class would be captured as well
      for (Method method : resourceClass.getMethods()) {
        String verb = httpVerbOf(method);
        if (verb == null) {
          continue;
        }
        Path methodPath = method.getAnnotation(Path.class);
        String fullPath = joinPaths(classPath, methodPath == null ? "" : methodPath.value());
        Produces methodProduces = method.getAnnotation(Produces.class);
        Consumes methodConsumes = method.getAnnotation(Consumes.class);
        List<String> produces = Arrays.asList(methodProduces != null ? methodProduces.value() : classProduces);
        List<String> consumes = Arrays.asList(methodConsumes != null ? methodConsumes.value() : classConsumes);
        List<String> parameterFacts = new ArrayList<>();
        boolean acceptsBody = false;
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
          String fact = describeParameter(parameters[i]);
          if (fact.startsWith("body")) {
            acceptsBody = true;
          }
          parameterFacts.add("param[" + i + "]=" + fact);
        }
        Operation apiOperation = method.getAnnotation(Operation.class);
        String apiOperationValue = apiOperation == null ? null : apiOperation.summary();
        endpoints.add(new Endpoint(method.getDeclaringClass().getSimpleName(), method.getName(), verb, fullPath,
            produces, consumes, parameterFacts, apiOperationValue, acceptsBody));
      }
    }
    endpoints.sort(Comparator.comparing(Endpoint::key));
    return endpoints;
  }

  /** The canonical surface description: one line per endpoint-fact, sorted. */
  static List<String> describeLines() {
    List<String> lines = new ArrayList<>();
    for (Endpoint endpoint : endpoints()) {
      String prefix = endpoint.key() + " ";
      lines.add(prefix + "class=" + endpoint.declaringClass + "." + endpoint.methodName);
      if (!endpoint.consumes.isEmpty()) {
        lines.add(prefix + "consumes=" + String.join(",", endpoint.consumes));
      }
      if (endpoint.apiOperationValue != null) {
        lines.add(prefix + "operation=\"" + endpoint.apiOperationValue + "\"");
      }
      for (String parameterFact : endpoint.parameterFacts) {
        lines.add(prefix + parameterFact);
      }
      if (!endpoint.produces.isEmpty()) {
        lines.add(prefix + "produces=" + String.join(",", endpoint.produces));
      }
    }
    lines.sort(Comparator.naturalOrder());
    return lines;
  }

  private static String describeParameter(Parameter parameter) {
    String type = simpleTypeName(parameter);
    PathParam pathParam = parameter.getAnnotation(PathParam.class);
    if (pathParam != null) {
      return "@PathParam(\"" + pathParam.value() + "\")" + defaultValueOf(parameter) + " " + type;
    }
    QueryParam queryParam = parameter.getAnnotation(QueryParam.class);
    if (queryParam != null) {
      return "@QueryParam(\"" + queryParam.value() + "\")" + defaultValueOf(parameter) + " " + type;
    }
    HeaderParam headerParam = parameter.getAnnotation(HeaderParam.class);
    if (headerParam != null) {
      return "@HeaderParam(\"" + headerParam.value() + "\")" + defaultValueOf(parameter) + " " + type;
    }
    if (parameter.isAnnotationPresent(Context.class) || parameter.isAnnotationPresent(Suspended.class)) {
      return "@Context " + type;
    }
    return "body " + type;
  }

  private static String defaultValueOf(Parameter parameter) {
    DefaultValue defaultValue = parameter.getAnnotation(DefaultValue.class);
    return defaultValue == null ? "" : " @DefaultValue(\"" + defaultValue.value() + "\")";
  }

  private static String simpleTypeName(Parameter parameter) {
    // java.util.Optional<java.lang.String> -> Optional<String>
    return parameter.getParameterizedType().getTypeName().replaceAll("[A-Za-z_$][A-Za-z0-9_$]*\\.", "");
  }

  private static String httpVerbOf(Method method) {
    for (Annotation annotation : method.getAnnotations()) {
      HttpMethod httpMethod = annotation.annotationType().getAnnotation(HttpMethod.class);
      if (httpMethod != null) {
        return httpMethod.value();
      }
    }
    return null;
  }

  private static String[] valueOf(Produces produces) {
    return produces == null ? new String[0] : produces.value();
  }

  private static String[] valueOf(Consumes consumes) {
    return consumes == null ? new String[0] : consumes.value();
  }

  private static String joinPaths(String classPath, String methodPath) {
    String left = classPath.endsWith("/") ? classPath.substring(0, classPath.length() - 1) : classPath;
    if (methodPath.isEmpty()) {
      return left;
    }
    return methodPath.startsWith("/") ? left + methodPath : left + "/" + methodPath;
  }

}
