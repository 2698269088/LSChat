using System;
using System.Windows.Forms;

namespace LS_Chat
{
    internal static class Program
    {
        [STAThread]
        public static void Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            AppConfig config = AppConfig.Load();
            var api = new ApiClient(config);

            // 首次启动：配置服务器地址
            if (string.IsNullOrEmpty(config.ServerHost))
            {
                using (var configForm = new ConfigForm(config, api))
                {
                    if (configForm.ShowDialog() != DialogResult.OK) return;
                }
            }

            // 登录 -> 主界面；注销后回到登录；真正退出时结束
            while (true)
            {
                if (string.IsNullOrEmpty(config.Token))
                {
                    using (var loginForm = new LoginForm(config, api))
                    {
                        if (loginForm.ShowDialog() != DialogResult.OK) return;
                    }
                }

                using (var mainForm = new MainForm(config, api))
                {
                    Application.Run(mainForm);
                    if (mainForm.LoggedOut)
                    {
                        config.Token = "";
                        config.Save();
                        continue;
                    }
                    return;
                }
            }
        }
    }
}
