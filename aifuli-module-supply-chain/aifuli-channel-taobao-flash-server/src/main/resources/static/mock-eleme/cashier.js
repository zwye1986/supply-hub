(function () {
    'use strict';

    var outTradeNo = '';
    var transactionId = '';
    var orderNo = '';
    var payAmountCents = 0;
    var isPaying = false;

    var API_BASE = (function () {
        var path = window.location.pathname;
        var idx = path.indexOf('/mock-eleme/');
        return idx >= 0 ? path.substring(0, idx) : '';
    })();

    function getUrlParams() {
        var params = {};
        var search = window.location.search.substring(1);
        if (!search) return params;
        search.split('&').forEach(function (pair) {
            var parts = pair.split('=');
            params[decodeURIComponent(parts[0].replace(/\+/g, '%20'))] =
                decodeURIComponent((parts[1] || '').replace(/\+/g, '%20'));
        });
        return params;
    }

    function yuan(cents) {
        var n = Number(cents || 0);
        return (n / 100).toFixed(2);
    }

    function init() {
        var params = getUrlParams();
        outTradeNo = params.outTradeNo || '';
        transactionId = params.transactionId || '';
        orderNo = params.orderNo || '';
        payAmountCents = Number(params.payAmount || 0);

        document.getElementById('outTradeNo').textContent = outTradeNo || '-';
        document.getElementById('transactionId').textContent = transactionId || '-';
        document.getElementById('orderNo').textContent = orderNo || '-';
        document.getElementById('totalPrice').textContent = yuan(payAmountCents);
        document.getElementById('payButton').addEventListener('click', pay);
        document.getElementById('resultBtn').addEventListener('click', function () {
            window.location.href = API_BASE + '/mock-eleme/index.html';
        });
    }

    function showLoading(show) {
        var el = document.getElementById('loading');
        if (show) el.classList.add('show'); else el.classList.remove('show');
    }

    function showResult(success, message, detail) {
        document.getElementById('resultIcon').textContent = success ? 'OK' : 'X';
        document.getElementById('resultMsg').textContent = message;
        document.getElementById('resultDetail').textContent = detail || '';
        document.getElementById('resultModal').classList.add('show');
    }

    function pay() {
        if (isPaying) return;
        if (!outTradeNo) {
            showResult(false, '缺少 outTradeNo', '请从 pay 跳转进入本页');
            return;
        }
        isPaying = true;
        var button = document.getElementById('payButton');
        button.disabled = true;
        showLoading(true);

        fetch(API_BASE + '/tbsg/mock-eleme/cashierPay', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json;charset=UTF-8' },
            body: JSON.stringify({
                outTradeNo: outTradeNo,
                transactionId: transactionId,
                orderNo: orderNo,
                payAmount: String(payAmountCents)
            })
        })
            .then(function (res) { return res.json(); })
            .then(function (data) {
                showLoading(false);
                if (data.success) {
                    showResult(true, '支付成功 (Mock)',
                        'outTradeNo: ' + outTradeNo + '\ntransactionId: ' + (data.transactionId || '-')
                        + '\n' + (data.message || ''));
                } else {
                    showResult(false, '支付失败', data.message || '未知错误');
                    button.disabled = false;
                    isPaying = false;
                }
            })
            .catch(function (err) {
                showLoading(false);
                showResult(false, '请求异常', err.message);
                button.disabled = false;
                isPaying = false;
            });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
