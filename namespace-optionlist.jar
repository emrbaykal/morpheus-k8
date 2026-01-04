var results = [];
var namespaces = data.namespaces || data;

var accountId = (typeof input !== "undefined" && input && input.accountId != null)
  ? input.accountId
  : null;

var isPlaceholder = false;
var descriptionFilter;

if (accountId == null || ("" + accountId).trim().length === 0) {
  descriptionFilter = "__PLACEHOLDER__";
  isPlaceholder = true;
} else {
  descriptionFilter = ("" + accountId).trim();
}

if (!namespaces || !namespaces.length) {
  if (isPlaceholder) {
    results;
  } else {
    throw "Namespace list not found in data. accountId=" + descriptionFilter;
  }
}

for (var i = 0; i < namespaces.length; i++) {
  var ns = namespaces[i];

  if (ns.active === false) continue;

  var desc = (ns.description == null) ? "" : ("" + ns.description).trim();
  if (desc !== descriptionFilter) continue;

  results.push({
    name: ns.name,
    value: ns.id
  });
}

if (!isPlaceholder && results.length === 0) {
  throw "No namespace found with description (accountId): " + descriptionFilter;
}