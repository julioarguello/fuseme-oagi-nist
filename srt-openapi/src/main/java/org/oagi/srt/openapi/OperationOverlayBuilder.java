package org.oagi.srt.openapi;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Generates RESTful CRUD operations from the super-schema's root ASCCP schemas.
 * Replicates Score's {@code OpenAPIGenerateExpression} operation patterns without
 * any database dependency — works purely from the schema map produced by
 * {@link OpenApiSchemaBuilder}.
 *
 * <p>Score conventions faithfully replicated:
 * <ul>
 *   <li>OAuth2 authorization code flow with per-resource Read/Write scopes</li>
 *   <li>{@code sinceLastDateTime} query parameter on GET collection endpoints</li>
 *   <li>{@code {id}} path parameter (type: string) on single-resource endpoints</li>
 *   <li>Tags derived from property term</li>
 *   <li>operationId pattern: {@code {verb}{SchemaName}[List]}</li>
 *   <li>List wrapper schemas: {@code {Name}List} → {@code type: array, items.$ref: {Name}}</li>
 * </ul>
 *
 * @see NamingHelper
 */
@Component
public class OperationOverlayBuilder {

	private static final String KEY_SUMMARY = "summary";
	private static final String KEY_DESCRIPTION = "description";
	private static final String KEY_SECURITY = "security";
	private static final String KEY_TAGS = "tags";
	private static final String KEY_OPERATION_ID = "operationId";
	private static final String KEY_PARAMETERS = "parameters";
	private static final String KEY_RESPONSES = "responses";
	private static final String KEY_REQUEST_BODY = "requestBody";
	private static final String KEY_SCHEMA = "schema";

	/**
	 * Add CRUD operations and supporting infrastructure (servers, security schemes,
	 * list wrapper schemas) to an existing OpenAPI document produced by
	 * {@link OpenApiSchemaBuilder}.
	 *
	 * <p>Root noun detection relies on {@code SuperTreeResult.getRootSchemaNames()},
	 * which is pre-filtered by {@code CcTreeWalker.walkAll()} to include only
	 * non-reusable, published ASCCPs. DataTypes, base ACCs, and Extension schemas
	 * are excluded upstream.
	 *
	 * @param doc              mutable OpenAPI document map (modified in place)
	 * @param rootSchemaNames  ASCCP root schema names (e.g., "PurchaseOrder", "Invoice")
	 */
	@SuppressWarnings("unchecked")
	public void addOperations(Map<String, Object> doc, Collection<String> rootSchemaNames) {
		Map<String, Object> components = (Map<String, Object>) doc.get("components");
		Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

		// Accumulate scopes for the global security scheme
		Map<String, Object> allScopes = new LinkedHashMap<>();
		Map<String, Object> paths = new LinkedHashMap<>();

		for (String schemaName : rootSchemaNames) {
			Map<String, Object> schemaObj = (Map<String, Object>) schemas.get(schemaName);
			if (schemaObj == null) {
				continue;
			}

			// Derive naming from the schema's x-oagis-den extension or fall back to schema name
			String propertyTerm = derivePropertyTerm(schemaName, schemaObj);
			String bieName = NamingHelper.bieName(propertyTerm);
			String resourcePath = NamingHelper.toResourcePath(schemaName);

			// OAuth2 scopes for this resource
			String readScope = NamingHelper.oauthScope(bieName, true);
			String writeScope = NamingHelper.oauthScope(bieName, false);
			allScopes.put(readScope, "Allows " + propertyTerm + " data to be read");
			allScopes.put(writeScope, "Allows " + propertyTerm + " data to be written");

			// Generate list wrapper schema
			String listSchemaName = schemaName + "List";
			schemas.put(listSchemaName, buildListWrapperSchema(schemaName));

			// Build path items
			paths.put(resourcePath, buildCollectionPathItem(
					schemaName, listSchemaName, readScope, writeScope, propertyTerm));
			paths.put(resourcePath + "/{id}", buildItemPathItem(
					schemaName, readScope, writeScope, propertyTerm));
		}

		// Add paths
		doc.put("paths", paths);

		// Add security schemes to components
		components.put("securitySchemes", buildSecuritySchemes(allScopes));
	}

	// -- Path item builders --------------------------------------------------

	/**
	 * Builds the collection path item ({@code /{resource}}) with GET (list) and POST.
	 * Matches Score's pattern for collection endpoints.
	 */
	private Map<String, Object> buildCollectionPathItem(
			String schemaName, String listSchemaName,
			String readScope, String writeScope, String propertyTerm) {

		Map<String, Object> pathItem = new LinkedHashMap<>();
		pathItem.put(KEY_SUMMARY, propertyTerm + " collection operations");
		pathItem.put(KEY_DESCRIPTION, "");

		// GET (list)
		Map<String, Object> getOp = new LinkedHashMap<>();
		getOp.put(KEY_SUMMARY, "");
		getOp.put(KEY_DESCRIPTION, "");
		getOp.put(KEY_SECURITY, buildSecurity(readScope));
		getOp.put(KEY_TAGS, Collections.singletonList(propertyTerm));
		getOp.put(KEY_OPERATION_ID, NamingHelper.operationId("GET", schemaName, true));
		getOp.put(KEY_PARAMETERS, buildCollectionParameters());
		getOp.put(KEY_RESPONSES, buildJsonResponse(listSchemaName));
		pathItem.put("get", getOp);

		// POST
		Map<String, Object> postOp = new LinkedHashMap<>();
		postOp.put(KEY_SUMMARY, "");
		postOp.put(KEY_DESCRIPTION, "");
		postOp.put(KEY_SECURITY, buildSecurity(writeScope));
		postOp.put(KEY_TAGS, Collections.singletonList(propertyTerm));
		postOp.put(KEY_OPERATION_ID, NamingHelper.operationId("POST", schemaName, false));
		postOp.put(KEY_REQUEST_BODY, buildJsonRequestBody(schemaName));
		postOp.put(KEY_RESPONSES, buildJsonResponse(schemaName));
		pathItem.put("post", postOp);

		return pathItem;
	}

	/**
	 * Builds the item path item ({@code /{resource}/{id}}) with GET, PUT, PATCH, DELETE.
	 * Matches Score's pattern for single-resource endpoints.
	 */
	private Map<String, Object> buildItemPathItem(
			String schemaName,
			String readScope, String writeScope, String propertyTerm) {

		Map<String, Object> pathItem = new LinkedHashMap<>();
		pathItem.put(KEY_SUMMARY, propertyTerm + " instance operations");
		pathItem.put(KEY_DESCRIPTION, "");

		List<Map<String, Object>> idParam = buildItemParameters();

		// GET (single)
		Map<String, Object> getOp = new LinkedHashMap<>();
		getOp.put(KEY_SUMMARY, "");
		getOp.put(KEY_DESCRIPTION, "");
		getOp.put(KEY_SECURITY, buildSecurity(readScope));
		getOp.put(KEY_TAGS, Collections.singletonList(propertyTerm));
		getOp.put(KEY_OPERATION_ID, NamingHelper.operationId("GET", schemaName, false));
		getOp.put(KEY_PARAMETERS, idParam);
		getOp.put(KEY_RESPONSES, buildJsonResponse(schemaName));
		pathItem.put("get", getOp);

		// PUT (replace)
		Map<String, Object> putOp = new LinkedHashMap<>();
		putOp.put(KEY_SUMMARY, "");
		putOp.put(KEY_DESCRIPTION, "");
		putOp.put(KEY_SECURITY, buildSecurity(writeScope));
		putOp.put(KEY_TAGS, Collections.singletonList(propertyTerm));
		putOp.put(KEY_OPERATION_ID, NamingHelper.operationId("PUT", schemaName, false));
		putOp.put(KEY_PARAMETERS, idParam);
		putOp.put(KEY_REQUEST_BODY, buildJsonRequestBody(schemaName));
		putOp.put(KEY_RESPONSES, buildJsonResponse(schemaName));
		pathItem.put("put", putOp);

		// PATCH (update)
		Map<String, Object> patchOp = new LinkedHashMap<>();
		patchOp.put(KEY_SUMMARY, "");
		patchOp.put(KEY_DESCRIPTION, "");
		patchOp.put(KEY_SECURITY, buildSecurity(writeScope));
		patchOp.put(KEY_TAGS, Collections.singletonList(propertyTerm));
		patchOp.put(KEY_OPERATION_ID, NamingHelper.operationId("PATCH", schemaName, false));
		patchOp.put(KEY_PARAMETERS, idParam);
		patchOp.put(KEY_REQUEST_BODY, buildJsonRequestBody(schemaName));
		patchOp.put(KEY_RESPONSES, buildJsonResponse(schemaName));
		pathItem.put("patch", patchOp);

		// DELETE
		Map<String, Object> deleteOp = new LinkedHashMap<>();
		deleteOp.put(KEY_SUMMARY, "");
		deleteOp.put(KEY_DESCRIPTION, "");
		deleteOp.put(KEY_SECURITY, buildSecurity(writeScope));
		deleteOp.put(KEY_TAGS, Collections.singletonList(propertyTerm));
		deleteOp.put(KEY_OPERATION_ID, NamingHelper.operationId("DELETE", schemaName, false));
		deleteOp.put(KEY_PARAMETERS, idParam);
		deleteOp.put(KEY_RESPONSES, buildEmptyResponse());
		pathItem.put("delete", deleteOp);

		return pathItem;
	}

	// -- Schema builders -----------------------------------------------------

	/**
	 * Builds a list wrapper schema following Score's convention:
	 * {@code {Name}List} → {@code type: array, items: $ref: #/components/schemas/{Name}}.
	 */
	private Map<String, Object> buildListWrapperSchema(String itemSchemaName) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "array");
		Map<String, Object> items = new LinkedHashMap<>();
		items.put("$ref", "#/components/schemas/" + itemSchemaName);
		schema.put("items", items);
		return schema;
	}

	// -- Request/Response builders -------------------------------------------

	private Map<String, Object> buildJsonRequestBody(String schemaName) {
		Map<String, Object> requestBody = new LinkedHashMap<>();
		requestBody.put(KEY_DESCRIPTION, "");
		requestBody.put("content", buildJsonContent(schemaName));
		return requestBody;
	}

	private Map<String, Object> buildJsonResponse(String schemaName) {
		Map<String, Object> responses = new LinkedHashMap<>();
		Map<String, Object> ok = new LinkedHashMap<>();
		ok.put(KEY_DESCRIPTION, "");
		ok.put("content", buildJsonContent(schemaName));
		responses.put("200", ok);
		return responses;
	}

	private Map<String, Object> buildEmptyResponse() {
		Map<String, Object> responses = new LinkedHashMap<>();
		Map<String, Object> ok = new LinkedHashMap<>();
		ok.put(KEY_DESCRIPTION, "");
		responses.put("200", ok);
		return responses;
	}

	private Map<String, Object> buildJsonContent(String schemaName) {
		Map<String, Object> content = new LinkedHashMap<>();
		Map<String, Object> json = new LinkedHashMap<>();
		Map<String, Object> schemaRef = new LinkedHashMap<>();
		schemaRef.put("$ref", "#/components/schemas/" + schemaName);
		json.put(KEY_SCHEMA, schemaRef);
		content.put("application/json", json);
		return content;
	}

	// -- Parameter builders --------------------------------------------------

	/**
	 * Builds collection-level parameters following Score's convention:
	 * the {@code sinceLastDateTime} query parameter for polling.
	 */
	private List<Map<String, Object>> buildCollectionParameters() {
		List<Map<String, Object>> params = new ArrayList<>();
		Map<String, Object> sinceParam = new LinkedHashMap<>();
		sinceParam.put("name", "sinceLastDateTime");
		sinceParam.put("in", "query");
		sinceParam.put(KEY_DESCRIPTION,
				"Returns resources that have been updated since the last time the endpoint has been called");
		sinceParam.put("required", false);
		Map<String, Object> sinceSchema = new LinkedHashMap<>();
		sinceSchema.put("type", "string");
		sinceSchema.put("format", "date-time");
		sinceParam.put(KEY_SCHEMA, sinceSchema);
		params.add(sinceParam);
		return params;
	}

	/**
	 * Builds item-level parameters following Score's convention:
	 * the {@code {id}} path parameter (type: string).
	 */
	private List<Map<String, Object>> buildItemParameters() {
		List<Map<String, Object>> params = new ArrayList<>();
		Map<String, Object> idParam = new LinkedHashMap<>();
		idParam.put("name", "id");
		idParam.put("in", "path");
		idParam.put(KEY_DESCRIPTION, "");
		idParam.put("required", true);
		Map<String, Object> idSchema = new LinkedHashMap<>();
		idSchema.put("type", "string");
		idParam.put(KEY_SCHEMA, idSchema);
		params.add(idParam);
		return params;
	}

	// -- Security builders ---------------------------------------------------

	/**
	 * Builds per-operation security requirement following Score's OAuth2 pattern.
	 */
	private List<Map<String, Object>> buildSecurity(String scope) {
		Map<String, Object> oauth2 = new LinkedHashMap<>();
		oauth2.put("OAuth2", Collections.singletonList(scope));
		return Collections.singletonList(oauth2);
	}

	/**
	 * Builds the global OAuth2 security scheme following Score's pattern:
	 * authorization code flow with example URLs.
	 */
	private Map<String, Object> buildSecuritySchemes(Map<String, Object> scopes) {
		Map<String, Object> schemes = new LinkedHashMap<>();
		Map<String, Object> oauth2 = new LinkedHashMap<>();
		oauth2.put("type", "oauth2");

		Map<String, Object> flows = new LinkedHashMap<>();
		Map<String, Object> authCode = new LinkedHashMap<>();
		authCode.put("authorizationUrl", "https://example.com/oauth/authorize");
		authCode.put("tokenUrl", "https://example.com/oauth/token");
		authCode.put("scopes", scopes);
		flows.put("authorizationCode", authCode);

		oauth2.put("flows", flows);
		schemes.put("OAuth2", oauth2);
		return schemes;
	}

	// -- Utility -------------------------------------------------------------

	/**
	 * Derives a human-readable property term from the schema, preferring the
	 * {@code x-oagis-den} extension if present, otherwise inserting spaces
	 * before uppercase letters in the schema name.
	 */
	private String derivePropertyTerm(String schemaName, Map<String, Object> schemaObj) {
		String fromDen = extractDenTerm(schemaObj);
		if (fromDen != null) {
			return fromDen;
		}
		return splitPascalCase(schemaName);
	}

	@SuppressWarnings("unchecked")
	private String extractDenTerm(Map<String, Object> schemaObj) {
		String term = parseDen(schemaObj.get("x-oagis-den"));
		if (term != null) {
			return term;
		}

		Object allOf = schemaObj.get("allOf");
		if (allOf instanceof List) {
			for (Object item : (List<Object>) allOf) {
				if (item instanceof Map) {
					term = parseDen(((Map<String, Object>) item).get("x-oagis-den"));
					if (term != null) {
						return term;
					}
				}
			}
		}
		return null;
	}

	private String parseDen(Object den) {
		if (den instanceof String && !((String) den).isEmpty()) {
			String denStr = (String) den;
			int dotIdx = denStr.indexOf(". ");
			return dotIdx > 0 ? denStr.substring(0, dotIdx) : denStr;
		}
		return null;
	}

	private String splitPascalCase(String name) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isUpperCase(c) && i > 0) {
				sb.append(' ');
			}
			sb.append(c);
		}
		return sb.toString();
	}
}
