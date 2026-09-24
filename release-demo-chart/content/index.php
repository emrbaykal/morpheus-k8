<?php
  // Release content lives in release.json; developers change that file to ship a new version.
  $release = json_decode(file_get_contents(__DIR__ . '/release.json'), true);
  $h = function ($v) { return htmlspecialchars((string) $v, ENT_QUOTES, 'UTF-8'); };
  $accent = preg_match('/^#[0-9a-fA-F]{6}$/', $release['accent'] ?? '') ? $release['accent'] : '#1f4e79';
?>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta http-equiv="refresh" content="2">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Release Demo v<?= $h($release['version']) ?></title>
  <style>
    body { margin: 0; font-family: "Segoe UI", Arial, sans-serif; background: #f4f5f7; color: #1d1d1f; }
    header { background: <?= $accent ?>; color: #fff; padding: 32px 24px; }
    header .label { font-size: 14px; letter-spacing: .08em; text-transform: uppercase; opacity: .8; }
    header .version { font-size: 64px; font-weight: 700; line-height: 1.1; margin: 4px 0; }
    header .title { font-size: 22px; }
    main { max-width: 880px; margin: 0 auto; padding: 24px; display: grid; gap: 24px; }
    section { background: #fff; border: 1px solid #dcdfe4; border-radius: 6px; padding: 20px 24px; }
    h2 { font-size: 15px; text-transform: uppercase; letter-spacing: .06em; margin: 0 0 12px; color: #555; }
    ul { margin: 0; padding-left: 20px; line-height: 1.7; }
    table { border-collapse: collapse; width: 100%; }
    td { padding: 6px 0; border-bottom: 1px solid #eee; }
    td:first-child { color: #555; width: 180px; }
    td:last-child { font-family: Menlo, Consolas, monospace; }
    footer { text-align: center; color: #777; font-size: 13px; padding-bottom: 24px; }
  </style>
</head>
<body>
  <header>
    <div class="label">Release Demo</div>
    <div class="version">v<?= $h($release['version']) ?></div>
    <div class="title"><?= $h($release['title']) ?></div>
  </header>
  <main>
    <section>
      <h2>What is new in this release</h2>
      <ul>
        <?php foreach (($release['notes'] ?? []) as $note): ?>
          <li><?= $h($note) ?></li>
        <?php endforeach; ?>
      </ul>
    </section>
    <section>
      <h2>Served by</h2>
      <table>
        <tr><td>Pod</td><td><?= $h(getenv('POD_NAME')) ?></td></tr>
        <tr><td>Node</td><td><?= $h(getenv('NODE_NAME')) ?></td></tr>
        <tr><td>Namespace</td><td><?= $h(getenv('NAMESPACE')) ?></td></tr>
        <tr><td>Chart version</td><td><?= $h(getenv('CHART_VERSION')) ?></td></tr>
        <tr><td>Helm revision</td><td><?= $h(getenv('HELM_REVISION')) ?></td></tr>
      </table>
    </section>
  </main>
  <footer>Page refreshes every 2 seconds &middot; <?= date('H:i:s') ?></footer>
</body>
</html>
