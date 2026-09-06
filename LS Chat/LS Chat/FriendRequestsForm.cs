using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;

namespace LS_Chat
{
    /// <summary>好友申请审批窗口：同意或拒绝他人发来的好友申请。</summary>
    public class FriendRequestsForm : Form
    {
        private readonly ApiClient _api;
        private readonly ListBox _list;
        private List<FriendRequest> _requests;

        public FriendRequestsForm(ApiClient api, List<FriendRequest> requests)
        {
            _api = api;
            _requests = requests != null ? new List<FriendRequest>(requests) : new List<FriendRequest>();

            Text = "好友申请";
            StartPosition = FormStartPosition.CenterParent;
            ClientSize = new Size(440, 340);
            MinimumSize = new Size(400, 300);

            _list = new ListBox
            {
                Dock = DockStyle.Fill,
                IntegralHeight = false,
                HorizontalScrollbar = true
            };
            var listPanel = new Panel { Dock = DockStyle.Fill, Padding = new Padding(10, 10, 10, 4) };
            listPanel.Controls.Add(_list);

            var buttons = new Panel { Dock = DockStyle.Bottom, Height = 46 };
            var btnApprove = new Button { Text = "同意", Left = 10, Top = 8, Width = 75 };
            btnApprove.Click += async (s, e) => await Handle(true);
            var btnReject = new Button { Text = "拒绝", Left = 95, Top = 8, Width = 75 };
            btnReject.Click += async (s, e) => await Handle(false);
            var btnRefresh = new Button { Text = "刷新", Left = 180, Top = 8, Width = 75 };
            btnRefresh.Click += async (s, e) => await RefreshRequests();
            var btnClose = new Button
            {
                Text = "关闭",
                Left = 350,
                Top = 8,
                Width = 75,
                Anchor = AnchorStyles.Top | AnchorStyles.Right,
                DialogResult = DialogResult.Cancel
            };
            buttons.Controls.Add(btnApprove);
            buttons.Controls.Add(btnReject);
            buttons.Controls.Add(btnRefresh);
            buttons.Controls.Add(btnClose);

            Controls.Add(listPanel);
            Controls.Add(buttons);
            AcceptButton = btnApprove;
            CancelButton = btnClose;

            ReloadList();
        }

        private void ReloadList()
        {
            _list.BeginUpdate();
            _list.Items.Clear();
            if (_requests.Count == 0)
            {
                _list.Items.Add("（暂无待处理的好友申请）");
            }
            else
            {
                foreach (FriendRequest request in _requests)
                {
                    _list.Items.Add(request);
                }
            }
            _list.EndUpdate();
        }

        private async System.Threading.Tasks.Task RefreshRequests()
        {
            try
            {
                _requests = await _api.FriendRequests();
                ReloadList();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "刷新失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private async System.Threading.Tasks.Task Handle(bool approve)
        {
            FriendRequest request = _list.SelectedItem as FriendRequest;
            if (request == null)
            {
                MessageBox.Show(this, "请先选择一条申请", "提示",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            try
            {
                await _api.HandleFriendRequest(request.id, approve);
                _requests.RemoveAll(r => r.id == request.id);
                ReloadList();
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, ex.Message, "操作失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }
    }
}
