// Option List "Groups" (REST, GET <appliance>/api/groups?max=200, inject execution lease auth).
// Groups the ordering user may provision into; value is the group id.
for (var i = 0; i < data.groups.length; i++) {
  var g = data.groups[i];
  results.push({name: g.name, value: g.id});
}
