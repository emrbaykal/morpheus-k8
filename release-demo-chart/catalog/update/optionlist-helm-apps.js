// Option List "Helm Apps" (REST, GET <appliance>/api/apps?max=200, inject execution lease auth,
// so it runs as the ordering user and only lists the apps that user can see).
// Translation Script: Helm apps only; value is the app id.
for (var i = 0; i < data.apps.length; i++) {
  var a = data.apps[i];
  if (a.type === 'helm') {
    results.push({name: a.name, value: a.id});
  }
}
