// @ts-check
// Note: type annotations allow type checking and IDEs autocompletion

const {themes} = require('prism-react-renderer');
const lightCodeTheme = themes.github;
const darkCodeTheme = themes.dracula;
const analyticsPlugin = require('./plugins/analytics.js');

/** @type {import('@docusaurus/types').Config} */
const config = {
    title: 'Neta - Java AIO 网络框架',
    tagline: 'Neta - Java AIO 网络框架',
    url: 'https://www.hasor.net',
    baseUrl: '/neta',
    onBrokenLinks: 'throw',
    onBrokenMarkdownLinks: 'warn',
    favicon: 'img/favicon.ico',
    organizationName: 'zycgit', // Usually your GitHub org/user name.
    projectName: 'neta',   // Usually your repo name.

    i18n: {
        defaultLocale: 'zh-cn',
        locales: ['zh-cn', 'en'],
    },

    presets: [
        [
            'classic',
            /** @type {import('@docusaurus/preset-classic').Options} */
            ({
                docs: {
                    sidebarPath: require.resolve('./sidebars.js'),
                    editUrl: 'https://gitee.com/zycgit/neta/tree/master/neta-doc',
                },
                theme: {
                    customCss: require.resolve('./src/css/custom.css'),
                },
            }),
        ],
    ],
    themeConfig: /** @type {import('@docusaurus/preset-classic').ThemeConfig} */ {
        metadata: [
            {name: 'keywords', content: 'aio,hasor,ssl,tls,spring,springboot,spring框架,网络框架,开源,开源软件,java开源,开源项目,开源代码'},
            {name: 'description', content: 'Neta is a network application framework that helps users to develop high performance and high scalability network applications easily. It provides an abstract asynchronous duplex programming mod.'}
        ],
        colorMode: {
            disableSwitch: true,
        },
        navbar: {
            logo: {
                alt: 'Neta Logo',
                src: 'img/logo.svg',
            },
            items: [
                {
                    position: 'left',
                    label: 'Neta 框架',
                    href: '/neta'
                },
                {
                    type: 'doc',
                    docId: 'releases/latest',
                    position: 'left',
                    label: '发布版本',
                },
                {
                    position: 'right',
                    label: 'QQ群 954915426',
                    href: 'https://qm.qq.com/cgi-bin/qm/qr?k=A76gGYcu8DLs-joJexnedc9WyQsXM410&jump_from=webapi&authKey=lIBY18bEW+F1HCTD3oCPlnNihlHw1cVhz77CwJg0UplyN2VZ/F3W04afFq4zSuUj'
                },
                {
                    position: 'right',
                    label: '码云',
                    href: 'https://gitee.com/zycgit/neta'
                },
                {
                    position: 'right',
                    label: 'Github',
                    href: 'https://github.com/zycgit/neta'
                },
                {
                    type: 'localeDropdown',
                    position: 'right',
                }
            ]
        },
        prism: {
            theme: lightCodeTheme,
            darkTheme: darkCodeTheme,
            additionalLanguages: ['java', 'bash', 'diff', 'json']
        },
        footer: {
            style: 'dark',
            copyright: `Copyright © ${new Date().getFullYear()} Neta. Built with Docusaurus.<br/>
<a target="_blank" href="http://www.beian.gov.cn/portal/registerSystemInfo?recordcode=33011002013536">
<img src="/img/beian.png" style="display: inline-block;">浙公网安备 33011002013536号
</a>&nbsp;&nbsp;<a target="_blank" href="https://beian.miit.gov.cn/#/Integrated/index">浙ICP备18034797号-1</a>
<div id="analyticsDiv" style="display: inline-block;"></div>`,
        },
    },
    plugins: [
        analyticsPlugin
    ],
    themes: [
        // ... Your other themes.
        [
            require.resolve("@easyops-cn/docusaurus-search-local"),
            /** @type {import("@easyops-cn/docusaurus-search-local").PluginOptions} */
            ({
                // ... Your options.
                // `hashed` is recommended as long-term-cache of index file is possible.
                hashed: true,
                // For Docs using Chinese, The `language` is recommended to set to:
                language: ["en", "zh"],
            }),
        ],
    ],
};

module.exports = config;
