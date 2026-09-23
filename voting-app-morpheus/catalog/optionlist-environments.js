// Option List "Environments" (REST, GET <appliance>/api/environments?max=100, inject execution
// lease auth). Value is the environment code used by the App Spec.
for (var i = 0; i < data.environments.length; i++) {
  var e = data.environments[i];
  results.push({name: e.name, value: e.code});
}
