#!/bin/bash
# =============================================================================
# release_demo_helm_upgrade.sh  (v1.1.0 - reads the form directly)
# v1.1.0: results.rdResolve.* was null in this Shell task on 9.0.2; the release
#         name, cluster id and replicas now come from customOptions.
# v1.0.0: first version
#
# Task 2 of the "Release Demo - Update" workflow. Shell Script task,
# EXECUTE TARGET = Local, GIT REPO = morpheus-k8, GIT REF = main.
# With GIT REPO set, Morpheus starts the script in its cached copy of the repo
# (library/automation/tasks.rst), so ./release-demo-chart is the chart as of
# the latest commit - no clone needed.
#
# Inputs come from the order form through Morpheus template substitution, which
# does run in Shell tasks (task 1 has already validated them).
# The cluster token is fetched here with the executing user's token and only
# lives in a mode-600 kubeconfig that is deleted on exit.
# =============================================================================
set -euo pipefail

API='<%= morpheus.applianceUrl %>'
TOKEN='<%= morpheus.apiAccessToken %>'
RELEASE='<%= customOptions.helmApp %>'
CLUSTER_ID='<%= customOptions.clusterId %>'
REPLICAS='<%= customOptions.replicas %>'
CHART=./release-demo-chart

command -v helm >/dev/null || { echo "helm is not installed on this Morpheus node"; exit 1; }
[ -f "$CHART/Chart.yaml" ] || { echo "Chart not found at $(pwd)/$CHART - is GIT REPO set on the task?"; exit 1; }

# Cluster API endpoint and token from Morpheus.
CFG=$(curl -sSk -H "Authorization: Bearer $TOKEN" "${API%/}/api/clusters/${CLUSTER_ID}/api-config")
SERVER=$(printf '%s' "$CFG" | sed -n 's/.*"serviceUrl":"\([^"]*\)".*/\1/p')
KTOKEN=$(printf '%s' "$CFG" | sed -n 's/.*"serviceToken":"\([^"]*\)".*/\1/p')
[ -n "$SERVER" ] && [ -n "$KTOKEN" ] || { echo "Could not read the API config of cluster $CLUSTER_ID"; exit 1; }

KCFG=$(mktemp); chmod 600 "$KCFG"; trap 'rm -f "$KCFG"' EXIT
cat > "$KCFG" <<KUBE
apiVersion: v1
kind: Config
clusters: [{name: c, cluster: {server: "$SERVER", insecure-skip-tls-verify: true}}]
users: [{name: u, user: {token: "$KTOKEN"}}]
contexts: [{name: x, context: {cluster: c, user: u}}]
current-context: x
KUBE
export KUBECONFIG="$KCFG"

# The namespace the release lives in.
NS=$(helm list -A -f "^${RELEASE}\$" -o json | sed -n 's/.*"namespace":"\([^"]*\)".*/\1/p' | head -1)
[ -n "$NS" ] || { echo "Helm release '$RELEASE' not found on cluster $CLUSTER_ID"; exit 1; }

echo "Upgrading release $RELEASE in namespace $NS to chart $(sed -n 's/^version: //p' $CHART/Chart.yaml), replicas $REPLICAS"
helm upgrade "$RELEASE" "$CHART" -n "$NS" --reuse-values --set replicaCount="$REPLICAS" --wait --timeout 5m
helm history "$RELEASE" -n "$NS" --max 3
