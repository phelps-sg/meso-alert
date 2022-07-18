export DEBIAN_FRONTEND=noninteractive

ln -fs /usr/share/zoneinfo/GMT /etc/localtime
apt-get install -y tzdata
dpkg-reconfigure --frontend noninteractive tzdata
