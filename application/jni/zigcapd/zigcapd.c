#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <termios.h>
#include <unistd.h>
#include <stdio.h>
#include <fcntl.h>
#include <stdint.h>
#include <limits.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <netinet/in.h>
#include <pcap.h>
#include <android/log.h>
//#include <android/log.h>
#include "serial.h"
#define LOG_TAG "Zigcap" // text for log tag 

//#define DEBUG_OUTPUT
#define VERBOSE

const char CHANGE_CHAN=0x0000;
const char TRANSMIT_PACKET=0x0001;
const char RECEIVED_PACKET=0x0002;
const char INITIALIZED=0x0003;

int set_channel(int channel);
void init_econotag();
char block_read1();
uint32_t block_read_uint32();
void block_read_nbytes(char *buf, int nbytes);
void debug_buf(char *buf, int length);
	
#define portname "/dev/ttyUSB1"
int fd;
char initialized_sequence[] = {0x67, 0x65, 0x6f, 0x72, 0x67, 0x65, 0x6e, 0x79, 0x63, 0x68, 0x69, 0x73};
const int SEQLEN=12;

struct pcap_pkthdr_32 {
	uint32_t sec;
	uint32_t usec;
	uint32_t caplen;	/* length of portion present */
	uint32_t len;	/* length this packet (off wire) */
};

int check_seq(char *buf1, char *buf2) {
	int i;
	for(i=0;i<SEQLEN;i++)
		if(buf1[i]!=buf2[i])
			return 0;

	return 1;
}

int main (int argc, char *argv[]) {
	int n;
  char cmd;
	struct pcap_file_header pcap_fh; 
	
	// TCP Server related variables
	int sd, sd_current;
	int addrlen;
	struct sockaddr_in sin, pin;
	char seq_buf[SEQLEN];
	int seqs_left=SEQLEN;

	// Initialize the econotag and a channel
	init_econotag();
	set_channel(1);
	
	if ((sd = socket(AF_INET, SOCK_STREAM, 0)) == -1) {
		perror("socket error");
		return -1;
	}
	
	// Listen on the user specified port
	memset(&sin, 0, sizeof(sin));
	sin.sin_family = AF_INET;
	sin.sin_addr.s_addr = INADDR_ANY;
	sin.sin_port = htons(atoi(argv[1]));
	
	// Bind to the port number
	if(bind(sd, (struct sockaddr *) &sin, sizeof(sin)) == -1) {
		perror("error trying to bind");
		return -1;
	}

	// Open a port on the user specified port
	if(listen(sd, 0) ==-1) {
		perror("error trying to listen on socket");
		return -1;
	}
	
	addrlen = sizeof(pin);
	if ((sd_current = accept(sd, (struct sockaddr *) &pin, &addrlen)) == -1) {
		perror("error trying to accept client");
		return -1;
	}

#ifdef VERBOSE
	__android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Accepted connection\n");
#endif


	// Wait for the firmware to report that the user has pushed the reset button and
	// restarted the firmware program.  Wait for the last N characters to meet our seq.
	while(seqs_left!=0) {
		char g1 = block_read1();
		seq_buf[SEQLEN-seqs_left]=g1;
#ifdef VERBOSE
			__android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Got the byte: 0x%x\n", g1);
#endif
	}

	while(!check_seq(seq_buf, initialized_sequence)) {
		int k;
		for(k=0;k<SEQLEN-1; k++)
			seq_buf[k]=seq_buf[k+1];

		seq_buf[SEQLEN-1]=block_read1();
	}
 
	write(sd_current, &INITIALIZED, 1);

#ifdef VERBOSE
	__android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Reset button push complete, going on...\n");
#endif


	// Need to construct a pcap file header for output (debugging)
	pcap_fh.magic = 0xa1b2c3d4;
	pcap_fh.version_major = 2;
	pcap_fh.version_minor = 4;
	pcap_fh.thiszone = 0;
	pcap_fh.sigfigs = 0;
	pcap_fh.snaplen = 0xffff;
	pcap_fh.linktype = 230;

#ifdef DEBUG_OUTPUT
	write(1, &pcap_fh, sizeof(struct pcap_file_header));
	fflush(stdout);
#endif

	// Keep reading in for commands
	while(1) {

		if((n = read(fd, &cmd, 1))==1) {
			
			if(cmd==RECEIVED_PACKET) {
				struct pcap_pkthdr_32 pcap_hdr;

				// Read in the fields
				uint8_t lqi = (uint8_t)block_read1();
				uint32_t rxtime = block_read_uint32();
				uint8_t length = (uint8_t)block_read1();

				// Read in the data
				char *buf = malloc(length);
				block_read_nbytes(buf, length);
				fprintf(stderr, "rxtime: %d, LQI: %d, length: %d\n", rxtime, lqi, length);

				// Write the command to the receive
				write(sd_current, &RECEIVED_PACKET, 1);
				
				// Construct a pcap packet header, using the time from 
				// the actual hardware, rather than time of day
				pcap_hdr.sec = 0;
				pcap_hdr.usec = 0;
				pcap_hdr.caplen = length;
				pcap_hdr.len = length;
#ifdef DEBUG_OUTPUT
				write(1, &pcap_hdr, sizeof(struct pcap_pkthdr_32));
				fflush(stdout);
#endif
				write(sd_current, &pcap_hdr, sizeof(struct pcap_pkthdr_32));

				// Now, print out the data
#ifdef DEBUG_OUTPUT
				write(1, buf, length);
				fflush(stdout);
#endif
				write(sd_current, buf, length);

				// Write the link quality indicator and received packet time
				write(sd_current, (char *)&rxtime, 4);
				write(sd_current, (char *)&lqi, 1);

				free(buf);
			}

		}
	}

	return 1;
}


char block_read1() {
	int n;
	char c;

	while(1) {
		if((n = read(fd, &c, 1))==1)
			return c;
	}
}

uint32_t block_read_uint32() {
	int i;
	uint32_t v = 0;

	for(i=0;i<4;i++) {
		uint32_t t = ((uint32_t)block_read1()) & 0xff;
		v = v | (t << i*CHAR_BIT);
	}

	return v;
}

void block_read_nbytes(char *buf, int nbytes) {
	int nread=0;
	while(nread<nbytes) {
		int n = read(fd, buf+nread, nbytes-nread);
		nread += n;
	}
}

void debug_buf(char *buf, int length) {
	int i=0;

	for(i=0;i<length;i++)
		fprintf(stderr, "0x%02x ", buf[i]);

	fprintf(stderr, "\n");
}

int set_channel(int channel) {
	char cmd = CHANGE_CHAN;
	char chan = (char) channel;
	//char rval;

	write (fd, &cmd, 1); 
	write (fd, &chan, 1);
	
	// read back value for testing
	/*read (fd, &rval, 1); 

	if(rval==chan)
		return 1;
	else
		return 0;*/
	return 1;
}

void init_econotag() {
	fd = open (portname, O_RDWR | O_NOCTTY | O_SYNC);

	if (fd < 0)
	{
		fprintf (stderr, "error %d opening %s", errno, portname);
		return;
	}

	set_interface_attribs (fd, B115200, 0);  // set speed to 115,200 bps, 8n1 (no parity)
	set_blocking (fd, 0);                // set no blocking
}
