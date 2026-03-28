package org.oagi.srt.openapi;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Naming convention utilities ported from Score's {@code Helper} class and
 * {@code SetOperationIdWithVerb}. Pure static methods with no DB dependency.
 *
 * <p>Score references:
 * <ul>
 *   <li>{@code Helper.camelCase()} — splits on spaces, capitalizes each word, lowercases first char</li>
 *   <li>{@code Helper.convertIdentifierToId()} — replaces "Identifier" with "Id"</li>
 *   <li>{@code SetOperationIdWithVerb.verbToOperationId()} — builds operation IDs with verb prefix</li>
 * </ul>
 */
public final class NamingHelper {

	private NamingHelper() {
		// Utility class
	}

	// -- Score Helper ports --------------------------------------------------

	/**
	 * Port of Score's {@code Helper.camelCase(String... terms)}.
	 * Splits on spaces, capitalizes each word, lowercases the leading character.
	 *
	 * <p>Example: "Purchase Order" → "purchaseOrder"
	 */
	public static String camelCase(String... terms) {
		String term = Arrays.stream(terms).collect(Collectors.joining());
		if (terms.length == 1) {
			term = toCamelCaseInternal(terms[0]);
		} else if (term.contains(" ")) {
			term = Arrays.stream(terms)
					.map(NamingHelper::toCamelCaseInternal)
					.collect(Collectors.joining());
		}

		if (term == null || term.isEmpty()) {
			throw new IllegalArgumentException("Cannot convert empty term to camelCase");
		}

		return Character.toLowerCase(term.charAt(0)) + term.substring(1);
	}

	private static String toCamelCaseInternal(String term) {
		return Arrays.stream(term.split(" "))
				.filter(e -> e != null && !e.isEmpty())
				.map(e -> {
					if (e.length() > 1) {
						return Character.toUpperCase(e.charAt(0)) + e.substring(1).toLowerCase();
					} else {
						return e.toUpperCase();
					}
				})
				.collect(Collectors.joining());
	}

	/**
	 * Port of Score's {@code Helper.convertIdentifierToId(String)}.
	 * Replaces "Identifier" → "Id" and "identifier" → "id".
	 */
	public static String convertIdentifierToId(String str) {
		if (str == null || str.isEmpty()) {
			return str;
		}
		return str.replace("Identifier", "Id")
				.replace("identifier", "id");
	}

	/**
	 * Derives the BIE-style name from an ASCCP property term, matching Score's
	 * {@code getBieName()} pattern: {@code convertIdentifierToId(camelCase(propertyTerm))}.
	 *
	 * <p>Example: "Purchase Order" → "purchaseOrder"
	 * <p>Example: "Party Identifier" → "partyId"
	 */
	public static String bieName(String propertyTerm) {
		return convertIdentifierToId(camelCase(propertyTerm));
	}

	// -- Path / URL conventions ----------------------------------------------

	/**
	 * Converts a PascalCase schema name to a kebab-case URL path segment,
	 * pluralized with a trailing "s".
	 *
	 * <p>Example: "PurchaseOrder" → "purchase-orders"
	 * <p>Example: "ItemQuantity" → "item-quantitys" (naive plural)
	 */
	public static String toResourcePath(String schemaName) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < schemaName.length(); i++) {
			char c = schemaName.charAt(i);
			if (Character.isUpperCase(c) && i > 0) {
				sb.append('-');
			}
			sb.append(Character.toLowerCase(c));
		}
		return "/" + sb.toString() + "s";
	}

	// -- Operation ID conventions (Score SetOperationIdWithVerb port) ---------

	/**
	 * HTTP verb → Score action verb mapping used in operationId construction.
	 *
	 * <p>Score convention:
	 * <ul>
	 *   <li>GET → "query"</li>
	 *   <li>POST → "create"</li>
	 *   <li>PUT → "replace"</li>
	 *   <li>PATCH → "update"</li>
	 *   <li>DELETE → "delete"</li>
	 * </ul>
	 */
	public static String verbToActionPrefix(String httpVerb) {
		switch (httpVerb.toUpperCase()) {
			case "GET":    return "query";
			case "POST":   return "create";
			case "PUT":    return "replace";
			case "PATCH":  return "update";
			case "DELETE": return "delete";
			default:
				throw new IllegalArgumentException("Unsupported HTTP verb: " + httpVerb);
		}
	}

	/**
	 * Builds an operationId following Score's {@code SetOperationIdWithVerb} convention.
	 *
	 * <p>Pattern: {@code {actionPrefix}{SchemaName}[List]}
	 *
	 * <p>Examples:
	 * <ul>
	 *   <li>GET + "PurchaseOrder" + isList=true → "queryPurchaseOrderList"</li>
	 *   <li>GET + "PurchaseOrder" + isList=false → "queryPurchaseOrder"</li>
	 *   <li>POST + "PurchaseOrder" → "createPurchaseOrder"</li>
	 *   <li>PATCH + "PurchaseOrder" → "updatePurchaseOrder"</li>
	 * </ul>
	 */
	public static String operationId(String httpVerb, String schemaName, boolean isList) {
		String prefix = verbToActionPrefix(httpVerb);
		return prefix + schemaName + (isList ? "List" : "");
	}

	// -- OAuth2 scope conventions --------------------------------------------

	/**
	 * Builds an OAuth2 scope name following Score's convention.
	 *
	 * <p>Pattern: {@code {bieName}Read} or {@code {bieName}Write}
	 *
	 * <p>Examples:
	 * <ul>
	 *   <li>"purchaseOrder" + read → "purchaseOrderRead"</li>
	 *   <li>"purchaseOrder" + write → "purchaseOrderWrite"</li>
	 * </ul>
	 */
	public static String oauthScope(String bieName, boolean isRead) {
		return bieName + (isRead ? "Read" : "Write");
	}
}
