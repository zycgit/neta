(function () {
    'use strict';

    function loadProtocolInfo() {
        var infoUrl = '/api/info';
        fetch(infoUrl)
            .then(function (res) { return res.json(); })
            .then(function (data) {
                setText('info-protocol', data.protocol || 'N/A');
                setText('info-scheme', data.scheme || 'N/A');
                setText('info-secure', data.secure ? 'Yes ✅' : 'No');
                setText('info-host', data.host || 'N/A');
                setText('info-remote', data.remoteAddress || 'N/A');
                setText('info-time', data.serverTime || 'N/A');

                updateProtocolCards(data);
            })
            .catch(function (err) {
                console.error('Failed to load protocol info:', err);
                setText('info-protocol', 'Error');
            });
    }

    function setText(id, value) {
        var el = document.getElementById(id);
        if (el) el.textContent = value;
    }

    function updateProtocolCards(data) {
        var config = data.serverConfig || {};
        toggleCard('card-h2', config.http2Enabled);
        toggleCard('card-h3', config.http3Enabled);
    }

    function toggleCard(id, enabled) {
        var card = document.getElementById(id);
        if (!card) return;
        var badge = card.querySelector('.badge');
        if (!badge) return;
        if (enabled) {
            badge.textContent = 'Active';
            badge.className = 'badge badge-active';
        } else {
            badge.textContent = 'Disabled';
            badge.className = 'badge badge-inactive';
        }
    }

    // expose to global for button onclick
    window.loadProtocolInfo = loadProtocolInfo;

    // auto-load on page ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', loadProtocolInfo);
    } else {
        loadProtocolInfo();
    }

    // auto-refresh every 30s
    setInterval(loadProtocolInfo, 30000);
})();
